package com.summit.runtime.audit;

import com.summit.core.agent.Execution;
import com.summit.core.audit.AuditStatus;
import com.summit.core.audit.DocumentElement;
import com.summit.core.audit.TokenAuditor;
import com.summit.core.audit.token.OtherTokens;
import com.summit.core.audit.token.TokenAuditDocument;
import com.summit.core.audit.token.tool.ToolAuditDocument;
import com.summit.core.audit.token.tool.ToolElement;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.MessageType;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.content.Content;
import com.summit.core.conversation.message.content.TextContent;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolRegistry;
import com.summit.runtime.agent.AgentConfig;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class DefaultTokenAuditor implements TokenAuditor {
    @Getter
    private final String MCP_LABEL = "MCP";
    @Getter
    private final String SKILL_LABEL = "SKILL";
    @Getter
    private final String UNKNOW_TOOL = "unknown_tool";
    private final Tokenizer tokenizer;
    private final AgentConfig agentConfig;
    private final ToolRegistry toolRegistry;
    private final Function<Execution, List<ToolDefinition<?>>> mcpToolDefinitions;

    /** Direct callers may bind one scope; shared auditors resolve definitions per execution. */
    public DefaultTokenAuditor(Tokenizer tokenizer, AgentConfig agentConfig, ToolRegistry toolRegistry,
                               McpToolScope mcpToolScope) {
        this(tokenizer, agentConfig, toolRegistry,
                execution -> (mcpToolScope == null ? McpToolScope.EMPTY : mcpToolScope).disclosedTools());
    }

    @Override
    public TokenAuditDocument analysis(Execution execution) {
        List<Message> messageList = Objects.requireNonNull(execution).getMessages();
        DocumentElement<Integer> assistantTokens = runIgnoreErr(MessageType.AI, messageList, () -> resolve(messageList, MessageType.AI));
        DocumentElement<Integer> userTokens = runIgnoreErr(MessageType.USER, messageList, () -> resolve(messageList, MessageType.USER));
        DocumentElement<Integer> systemTokens = runIgnoreErr(MessageType.SYSTEM, messageList, () -> resolve(messageList, MessageType.SYSTEM));
        DocumentElement<ToolAuditDocument> toolTokens = runIgnoreErr(MessageType.TOOL, messageList, () -> resolveTool(messageList));
        return new TokenAuditDocument(assistantTokens, userTokens, toolTokens, systemTokens, resolveOtherTokens(execution));
    }


    private DocumentElement<Integer> resolve(List<Message> messageList, MessageType type) {
        var waitingEstimate = new ArrayList<Message>();

        for (Message message : messageList) {
            if (type == message.type()) {
                waitingEstimate.add(message);
            }
        }

        return estimateUsage(type.name(), waitingEstimate, ContextUsageMetric::tokenCount);


    }


    private DocumentElement<ToolAuditDocument> resolveTool(List<Message> messages) {
        final Map<String, List<Message>> aggregatedMap = new HashMap<>();  // tool name -> messageList
        final Map<String, ToolElement> detailMap = new HashMap<>();        // tool name -> (argumentTokenCounts, outputTokenCounts, totalTokenCounts)
        final Map<String, Integer> callArgumentTokens = new HashMap<>();   // toolCall id -> tokenCounts of arguments
        final Map<ToolMessageEntity, Integer> argumentTokensByMessage = new IdentityHashMap<>(); // ToolMessage -> tokenCount
        final List<Message> mcpList = new ArrayList<>();
        final List<Message> skillList = new ArrayList<>();
        final List<ToolMessageEntity> toolList = new ArrayList<>();

        for (Message message : messages) {
            if (message instanceof AiMessageEntity ai && ai.getToolCalls() != null) {
                for (ToolCallRequest call : ai.getToolCalls()) {
                    callArgumentTokens.put(call.id(), this.tokenizer.countText(call.arguments()));
                }
            }
            if (message instanceof ToolMessageEntity tool) {
                String id = tool.getId() == null ? null : tool.getId().toString();
                Integer argumentTokens = id == null ? null : callArgumentTokens.remove(id);
                if (argumentTokens == null) {
                    throw new IllegalArgumentException("Missing tool call for result: " + tool.getId());
                }
                toolList.add(tool);
                argumentTokensByMessage.put(tool, argumentTokens);
            }
        }

        for (ToolMessageEntity toolMessage : toolList) {
            if (toolMessage.isMcpTool()) {
                mcpList.add(toolMessage);
                continue;
            } else if (toolMessage.isSkillTool()) {
                skillList.add(toolMessage);
                continue;
            }

            String name = Objects.requireNonNullElse(toolMessage.getName(), UNKNOW_TOOL);

            List<Message> list = aggregatedMap.computeIfAbsent(name, n -> new ArrayList<>());

            list.add(toolMessage);
        }

        for (Map.Entry<String, List<Message>> entry : aggregatedMap.entrySet()) {
            detailMap.put(entry.getKey(), toolElementsOf(entry.getValue(), argumentTokensByMessage,
                    this.tokenizer.count(entry.getValue())));
        }

        // Arguments belong to assistant messages and must not be added to tool-result totals.
        int totalTokens = this.tokenizer.count(new ArrayList<>(toolList));

        double ratio = totalTokens / (double) this.agentConfig.maxTokens();

        DocumentElement<ToolElement> mcpDoc = estimateUsage(MCP_LABEL, mcpList,
                usage -> toolElementsOf(mcpList, argumentTokensByMessage, usage.tokenCount()));
        DocumentElement<ToolElement> skillDoc = estimateUsage(SKILL_LABEL, skillList,
                usage -> toolElementsOf(skillList, argumentTokensByMessage, usage.tokenCount()));
        if (mcpDoc.getStatus() == AuditStatus.SUCCESS) {
            mcpDoc.setRatio(mcpDoc.getTokenMeta().totalTokens() / (double) this.agentConfig.maxTokens());
        }
        if (skillDoc.getStatus() == AuditStatus.SUCCESS) {
            skillDoc.setRatio(skillDoc.getTokenMeta().totalTokens() / (double) this.agentConfig.maxTokens());
        }

        ToolAuditDocument toolAuditDocument = new ToolAuditDocument(totalTokens, mcpDoc, skillDoc, detailMap);
        return DocumentElement.<ToolAuditDocument>builder()
                .tokenMeta(toolAuditDocument)
                .ratio(ratio)
                .type(MessageType.TOOL.name())
                .status(AuditStatus.SUCCESS)
                .build();
    }

    private <T> DocumentElement<T> estimateUsage(String type, List<Message> messages, Function<ContextUsageMetric, T> resolveElement) {
        ContextUsageMetric usage = this.tokenizer.usage(messages, List.of(), agentConfig.maxTokens());
        DocumentElement.DocumentElementBuilder<T> element = DocumentElement.<T>builder().type(type);
        if (usage == null) {
            return element.status(AuditStatus.NO_DATA).build();
        }
        return element.tokenMeta(resolveElement.apply(usage))
                .ratio(usage.ratio())
                .status(AuditStatus.SUCCESS)
                .build();
    }


    private OtherTokens resolveOtherTokens(Execution execution) {
        var definitions = runAudit("TOOL_DEFINITION", true,
                () -> toolDefOf(this.toolRegistry.registeredTools(execution)));
        var mcpDefinitions = runAudit("MCP_TOOL_DEFINITION", true,
                () -> toolDefOf(this.mcpToolDefinitions.apply(execution)));
        var memory = runAudit("MEMORY", true,
                () -> textOf(execution.getAgentRequest().getContextSpec().getMemory()));
        var attachments = runAudit("ATTACHMENT", execution.getMessages() != null,
                () -> attachmentsOf(execution.getMessages()));
        return new OtherTokens(definitions, mcpDefinitions, memory, attachments);
    }

    private DocumentElement<Integer> attachmentsOf(List<Message> messages) {
        int count = 0;
        for (Message message : messages) {
            if (!(message instanceof UserMessageEntity user) || user.getContent() == null) continue;
            for (Content content : user.getContent()) {
                Objects.requireNonNull(content, "User content part must not be null");
                if (!(content instanceof TextContent)) {
                    count += this.tokenizer.count(content);
                }
            }
        }
        return countOf(count);
    }

    private ToolElement toolElementsOf(List<Message> tmes,
                                       Map<ToolMessageEntity, Integer> argumentTokensByMessage,
                                       int resultMessageTokens
    ) {
        int argumentTokens = 0, outputTokens = 0;
        for (Message message : tmes) {
            if (message instanceof ToolMessageEntity tme) {
                outputTokens += this.tokenizer.countText(tme.getToolOutput());
                argumentTokens += argumentTokensByMessage.get(tme);
            }
        }

        return new ToolElement(argumentTokens, outputTokens, resultMessageTokens);
    }

    private <T> DocumentElement<T> runIgnoreErr(MessageType messageType, List<Message> messages,
                                                Callable<DocumentElement<T>> callable) {
        return runAudit(messageType.name(), messages != null, callable);
    }

    private <T> DocumentElement<T> runAudit(String type, boolean hasData,
                                          Callable<DocumentElement<T>> callable) {
        if (!hasData) {
            return DocumentElement.<T>builder().type(type).status(AuditStatus.NO_DATA).build();
        }
        try {
            DocumentElement<T> result = callable.call();
            result.setType(type);
            return result;
        } catch (Exception e) {
            log.error("Failed to conduct the audit, type: {}", type, e);
            return DocumentElement.<T>builder()
                    .type(type)
                    .status(AuditStatus.FAILED)
                    .auditDes(e.getMessage())
                    .build();
        }
    }

    private DocumentElement<Integer> textOf(List<String> text) {
        // Match the body rendered by withMemoryPrompt, including its newline separators.
        String body = text == null ? "" : text.stream()
                .filter(Objects::nonNull).filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
        return countOf(this.tokenizer.countText(body));
    }

    private DocumentElement<Integer> countOf(int count) {
        return DocumentElement.<Integer>builder()
                .ratio(count / (double) agentConfig.maxTokens())
                .status(AuditStatus.SUCCESS)
                .tokenMeta(count)
                .build();
    }


    private DocumentElement<Integer> toolDefOf(List<ToolDefinition<?>> toolDefinitions){

        int defCount = this.tokenizer.count(List.of(), new ArrayList<>(toolDefinitions)); // Don't calculate the history again

        return countOf(defCount);
    }
}
