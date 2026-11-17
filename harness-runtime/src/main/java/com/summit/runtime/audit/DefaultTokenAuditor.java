package com.summit.runtime.audit;

import com.summit.core.agent.Execution;
import com.summit.core.audit.AuditStatus;
import com.summit.core.audit.DocumentElement;
import com.summit.core.audit.TokenAuditor;
import com.summit.core.audit.token.OtherTokens;
import com.summit.core.audit.token.TokenAuditDocument;
import com.summit.core.audit.token.tool.ToolAuditDocument;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.MessageType;
import com.summit.core.conversation.message.content.Content;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolRegistry;
import com.summit.core.tool.ToolVisibility;
import com.summit.runtime.agent.AgentConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

@Slf4j
public class DefaultTokenAuditor implements TokenAuditor {
    public static final String MCP_LABEL = ToolTokenAuditor.MCP_LABEL;
    public static final String SKILL_LABEL = ToolTokenAuditor.SKILL_LABEL;
    public static final String MEMORY_LABEL = "MEMORY";
    public static final String DYNAMIC_CONTEXT_LABEL = "DYNAMIC_CONTEXT";
    public static final String ATTACHMENT_LABEL = "ATTACHMENT";
    public static final String MCP_DEF_LABEL = "MCP_TOOL_DEFINITION";
    public static final String TOOL_DEF_LABEL = "TOOL_DEFINITION";

    @Getter
    private final String UNKNOW_TOOL = ToolTokenAuditor.UNKNOWN_TOOL;
    private final Tokenizer tokenizer;
    private final AgentConfig agentConfig;
    private final ToolRegistry toolRegistry;
    private final ToolTokenAuditor toolAuditor;

    public DefaultTokenAuditor(Tokenizer tokenizer, AgentConfig agentConfig, ToolRegistry toolRegistry) {
        this.tokenizer = tokenizer;
        this.agentConfig = agentConfig;
        this.toolRegistry = toolRegistry;
        this.toolAuditor = new ToolTokenAuditor(tokenizer, agentConfig);
    }

    @Override
    public TokenAuditDocument analysis(Execution execution) {
        AuditContext context = AuditContext.fromHistory(Objects.requireNonNull(execution).getMessages());
        boolean hasHistory = context.hasHistory();
        DocumentElement<Integer> assistant = runAudit(MessageType.AI.name(), hasHistory,
                () -> estimateMessages(context.assistantMessages()));
        DocumentElement<Integer> user = runAudit(MessageType.USER.name(), hasHistory,
                () -> estimateMessages(context.userMessages()));
        DocumentElement<Integer> system = runAudit(MessageType.SYSTEM.name(), hasHistory,
                () -> estimateMessages(context.systemMessages()));
        DocumentElement<ToolAuditDocument> tools = runAudit(MessageType.TOOL.name(), hasHistory,
                () -> toolAuditor.audit(context));
        return new TokenAuditDocument(assistant, user, tools, system, resolveOtherTokens(execution, context));
    }

    private DocumentElement<Integer> estimateMessages(List<Message> messages) {
        ContextUsageMetric usage = tokenizer.usage(messages, List.of(), agentConfig.maxTokens());
        if (usage == null) {
            return DocumentElement.<Integer>builder().status(AuditStatus.NO_DATA).build();
        }
        return DocumentElement.<Integer>builder()
                .tokenMeta(usage.tokenCount())
                .ratio(usage.ratio())
                .status(AuditStatus.SUCCESS)
                .build();
    }

    private OtherTokens resolveOtherTokens(Execution execution, AuditContext context) {
        DefinitionAudits definitions = auditDefinitions(execution);
        // The per-round block is its own bucket: it is neither the application's system prompt nor
        // something the user typed, and folding it into either one is what made the numbers lie.
        DocumentElement<Integer> dynamicContext = runAudit(DYNAMIC_CONTEXT_LABEL, context.hasHistory(),
                () -> estimateMessages(context.dynamicContextMessages()));
        List<DocumentElement<Integer>> sections = resolveDynamicSections(context);
        DocumentElement<Integer> memory = runAudit(MEMORY_LABEL, true, () -> auditMemory(sections));
        DocumentElement<Integer> attachments = runAudit(ATTACHMENT_LABEL, context.hasHistory(),
                () -> auditAttachments(context.attachments()));
        return new OtherTokens(definitions.registered(), definitions.mcp(), dynamicContext, memory, sections, attachments);
    }

    private List<DocumentElement<Integer>> resolveDynamicSections(AuditContext context) {
        if (!context.hasHistory()) {
            return List.of();
        }
        List<DocumentElement<Integer>> sections = new ArrayList<>();
        for (AuditContext.DynamicSectionSpan span : context.dynamicContextSections()) {
            sections.add(runAudit(span.kind().name(), true, () -> countOf(tokenizer.countText(span.body()))));
        }
        return sections;
    }

    private DocumentElement<Integer> auditMemory(List<DocumentElement<Integer>> sections) {
        // The very element counted for the MEMORY slice: one source, one number, no re-derivation
        // from the request — that path drifted whenever the request and the message disagreed.
        return sections.stream()
                .filter(element -> MEMORY_LABEL.equals(element.getType()))
                .findFirst()
                .orElseGet(() -> countOf(0));
    }

    private DefinitionAudits auditDefinitions(Execution execution) {
        List<? extends ToolDefinition<?>> registered;
        List<String> allowed;
        try {
            registered = toolRegistry.getToolDefinitionList();
            allowed = execution.getAgentRequest().toolSpecOrDefault().getToolList();
        } catch (Exception failure) {
            return new DefinitionAudits(failed(TOOL_DEF_LABEL, failure), failed(MCP_DEF_LABEL, failure));
        }

        // No remote tool is ever declared on its own: every one is called through the gateway, which
        // is a registered definition. Remote schemas are paid for inside lookup results, i.e. in the
        // tool messages, so the remote definition bucket is empty by construction.
        return new DefinitionAudits(
                runAudit(TOOL_DEF_LABEL, true, () -> countDefinitions(ToolVisibility.select(registered, allowed))),
                countOf(0));
    }

    private DocumentElement<Integer> countDefinitions(List<ToolDefinition<?>> definitions) {
        return countOf(tokenizer.count(List.of(), new ArrayList<>(definitions)));
    }

    private DocumentElement<Integer> auditAttachments(List<Content> attachments) {
        int count = 0;
        for (Content attachment : attachments) {
            count += tokenizer.count(Objects.requireNonNull(attachment, "User content part must not be null"));
        }
        return countOf(count);
    }

    private DocumentElement<Integer> countOf(int count) {
        return DocumentElement.<Integer>builder()
                .ratio(count / (double) agentConfig.maxTokens())
                .status(AuditStatus.SUCCESS)
                .tokenMeta(count)
                .build();
    }

    private <T> DocumentElement<T> runAudit(String type, boolean hasData, Callable<DocumentElement<T>> callable) {
        if (!hasData) {
            return DocumentElement.<T>builder().type(type).status(AuditStatus.NO_DATA).build();
        }
        try {
            DocumentElement<T> result = callable.call();
            result.setType(type);
            return result;
        } catch (Exception failure) {
            return failed(type, failure);
        }
    }

    private <T> DocumentElement<T> failed(String type, Exception failure) {
        log.error("Failed to conduct the audit, type: {}", type, failure);
        return DocumentElement.<T>builder()
                .type(type)
                .status(AuditStatus.FAILED)
                .auditDes(failure.getMessage())
                .build();
    }

    private record DefinitionAudits(DocumentElement<Integer> registered, DocumentElement<Integer> mcp) {}
}
