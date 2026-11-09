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
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolRegistry;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.tool.ModelToolVisibility;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class DefaultTokenAuditor implements TokenAuditor {
    public static final String MCP_LABEL = ToolTokenAuditor.MCP_LABEL;
    public static final String SKILL_LABEL = ToolTokenAuditor.SKILL_LABEL;
    public static final String MEMORY_LABEL = "MEMORY";
    public static final String ATTACHMENT_LABEL = "ATTACHMENT";
    public static final String MCP_DEF_LABEL = "MCP_TOOL_DEFINITION";
    public static final String TOOL_DEF_LABEL = "TOOL_DEFINITION";

    @Getter
    private final String UNKNOW_TOOL = ToolTokenAuditor.UNKNOWN_TOOL;
    private final Tokenizer tokenizer;
    private final AgentConfig agentConfig;
    private final ToolRegistry toolRegistry;
    private final Function<Execution, List<ToolDefinition<?>>> mcpToolDefinitions;
    private final ToolTokenAuditor toolAuditor;

    public DefaultTokenAuditor(Tokenizer tokenizer, AgentConfig agentConfig, ToolRegistry toolRegistry,
                               Function<Execution, List<ToolDefinition<?>>> mcpToolDefinitions) {
        this.tokenizer = tokenizer;
        this.agentConfig = agentConfig;
        this.toolRegistry = toolRegistry;
        this.mcpToolDefinitions = mcpToolDefinitions;
        this.toolAuditor = new ToolTokenAuditor(tokenizer, agentConfig);
    }

    /** Direct callers may bind one scope; shared auditors resolve definitions per execution. */
    public DefaultTokenAuditor(Tokenizer tokenizer, AgentConfig agentConfig, ToolRegistry toolRegistry,
                               McpToolScope mcpToolScope) {
        this(tokenizer, agentConfig, toolRegistry,
                execution -> (mcpToolScope == null ? McpToolScope.EMPTY : mcpToolScope).disclosedTools());
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
        DocumentElement<Integer> memory = runAudit(MEMORY_LABEL, true,
                () -> auditMemory(execution.getAgentRequest().getContextSpec().getMemory()));
        DocumentElement<Integer> attachments = runAudit(ATTACHMENT_LABEL, context.hasHistory(),
                () -> auditAttachments(context.attachments()));
        return new OtherTokens(definitions.registered(), definitions.mcp(), memory, attachments);
    }

    private DefinitionAudits auditDefinitions(Execution execution) {
        List<? extends ToolDefinition<?>> registered;
        List<String> allowed;
        try {
            registered = toolRegistry.getToolDefinitionList();
            allowed = execution.getAgentRequest().getToolList();
        } catch (Exception failure) {
            return new DefinitionAudits(failed(TOOL_DEF_LABEL, failure), failed(MCP_DEF_LABEL, failure));
        }

        ModelToolVisibility.Selection selection;
        try {
            selection = ModelToolVisibility.select(registered, mcpToolDefinitions.apply(execution), allowed);
        } catch (Exception failure) {
            return new DefinitionAudits(
                    runAudit(TOOL_DEF_LABEL, true,
                            () -> countDefinitions(ModelToolVisibility.select(registered, List.of(), allowed).registered())),
                    failed(MCP_DEF_LABEL, failure));
        }

        DocumentElement<Integer> mcp = runAudit(MCP_DEF_LABEL, true, () -> countDefinitions(selection.mcp()));
        return new DefinitionAudits(
                runAudit(TOOL_DEF_LABEL, true, () -> countDefinitions(selection.registered())), mcp);
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

    private DocumentElement<Integer> auditMemory(List<String> memory) {
        // Match the body rendered by withMemoryPrompt, including its newline separators.
        String body = memory == null ? "" : memory.stream()
                .filter(Objects::nonNull).filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
        return countOf(tokenizer.countText(body));
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
