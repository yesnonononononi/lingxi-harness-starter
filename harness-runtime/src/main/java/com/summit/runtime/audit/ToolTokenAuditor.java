package com.summit.runtime.audit;

import com.summit.core.audit.AuditStatus;
import com.summit.core.audit.DocumentElement;
import com.summit.core.audit.token.tool.ToolAuditDocument;
import com.summit.core.audit.token.tool.ToolElement;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.audit.AuditContext.ToolResult;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Counts retained calls and their results, with result details grouped by provenance. */
@RequiredArgsConstructor
final class ToolTokenAuditor {
    static final String MCP_LABEL = "MCP";
    static final String SKILL_LABEL = "SKILL";
    static final String UNKNOWN_TOOL = "unknown_tool";

    private final Tokenizer tokenizer;
    private final AgentConfig agentConfig;

    DocumentElement<ToolAuditDocument> audit(AuditContext context) {
        ArgumentTokens arguments = countArguments(context.toolCalls());
        ResultGroups groups = groupResults(context.toolResults());
        Map<String, ToolElement> details = buildOrdinaryDetails(groups.ordinary(), arguments);
        int total = tokenizer.count(messages(context.toolResults())) + arguments.total();
        DocumentElement<ToolElement> mcp = auditGroup(MCP_LABEL, groups.mcp(), arguments);
        DocumentElement<ToolElement> skill = auditGroup(SKILL_LABEL, groups.skill(), arguments);

        return DocumentElement.<ToolAuditDocument>builder()
                .tokenMeta(new ToolAuditDocument(total, mcp, skill, details))
                .ratio(total / (double) agentConfig.maxTokens())
                .status(AuditStatus.SUCCESS)
                .build();
    }

    private ArgumentTokens countArguments(List<ToolCallRequest> calls) {
        Map<ToolCallRequest, Integer> byCall = new IdentityHashMap<>();
        int total = 0;
        for (ToolCallRequest call : calls) {
            int tokens = tokenizer.countText(call.arguments());
            byCall.put(call, tokens);
            // Retained arguments also occupy context when their result is absent.
            total += tokens;
        }
        return new ArgumentTokens(total, byCall);
    }

    private ResultGroups groupResults(List<ToolResult> results) {
        Map<String, List<ToolResult>> ordinary = new HashMap<>();
        List<ToolResult> mcp = new ArrayList<>();
        List<ToolResult> skill = new ArrayList<>();
        for (ToolResult result : results) {
            ToolMessageEntity message = result.message();
            if (result.call() == null) {
                throw new IllegalArgumentException("Missing tool call for result: " + message.getId());
            }
            if (message.isMcpTool()) {
                mcp.add(result);
            } else if (message.isSkillTool()) {
                skill.add(result);
            } else {
                String name = Objects.requireNonNullElse(message.getName(), UNKNOWN_TOOL);
                ordinary.computeIfAbsent(name, ignored -> new ArrayList<>()).add(result);
            }
        }
        return new ResultGroups(ordinary, mcp, skill);
    }

    private Map<String, ToolElement> buildOrdinaryDetails(Map<String, List<ToolResult>> groups,
                                                           ArgumentTokens arguments) {
        Map<String, ToolElement> details = new HashMap<>();
        groups.forEach((name, results) -> {
            int resultTokens = tokenizer.count(messages(results));
            details.put(name, buildDetail(results, arguments, resultTokens));
        });
        return details;
    }

    private DocumentElement<ToolElement> auditGroup(String label, List<ToolResult> results, ArgumentTokens arguments) {
        ContextUsageMetric usage = tokenizer.usage(messages(results), List.of(), agentConfig.maxTokens());
        DocumentElement.DocumentElementBuilder<ToolElement> element = DocumentElement.<ToolElement>builder().type(label);
        if (usage == null) {
            return element.status(AuditStatus.NO_DATA).build();
        }

        ToolElement detail = buildDetail(results, arguments, usage.tokenCount());
        return element.tokenMeta(detail)
                .ratio(detail.totalTokens() / (double) agentConfig.maxTokens())
                .status(AuditStatus.SUCCESS)
                .build();
    }

    private ToolElement buildDetail(List<ToolResult> results, ArgumentTokens arguments, int resultTokens) {
        int argumentTokens = 0;
        int outputTokens = 0;
        for (ToolResult result : results) {
            outputTokens += tokenizer.countText(result.message().getToolOutput());
            argumentTokens += arguments.byCall().get(result.call());
        }
        return new ToolElement(argumentTokens, outputTokens, resultTokens + argumentTokens);
    }

    private static List<Message> messages(List<ToolResult> results) {
        List<Message> messages = new ArrayList<>(results.size());
        for (ToolResult result : results) {
            messages.add(result.message());
        }
        return messages;
    }

    private record ArgumentTokens(int total, Map<ToolCallRequest, Integer> byCall) {}

    private record ResultGroups(Map<String, List<ToolResult>> ordinary, List<ToolResult> mcp,
                                List<ToolResult> skill) {}
}
