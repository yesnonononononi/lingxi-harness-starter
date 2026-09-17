package com.summit.runtime;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.runtime.AgentLoopHook;
import com.summit.core.runtime.LoopTurnResult;
import com.summit.core.tool.LoopBoundary;
import com.summit.core.tool.ToolExecuteResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** Applies product-neutral instructions returned by an application {@link AgentLoopHook}. */
@RequiredArgsConstructor
public final class LoopTurnCoordinator {

    public enum Decision { CONTINUE, CLOSE_NATURALLY, STOP }

    private final RuntimeContext context;
    @Getter private boolean executeBoundarySelected;
    @Getter private boolean stopped;

    public Decision onPlainTextTurn(Execution execution, Serializable sessionId) {
        LoopTurnResult.Action action = apply(execution, sessionId,
                hook().onPlainTextTurn(execution, sessionId));
        if (action == LoopTurnResult.Action.CONTINUE || action == LoopTurnResult.Action.SWITCH_TO_EXECUTE) {
            return Decision.CONTINUE;
        }
        return action == LoopTurnResult.Action.NONE ? Decision.CLOSE_NATURALLY : Decision.STOP;
    }

    public void afterToolTurn(Execution execution, Serializable sessionId, List<ToolExecuteResult> results) {
        apply(execution, sessionId, hook().afterToolTurn(results, execution, sessionId));
    }

    private LoopTurnResult.Action apply(Execution execution, Serializable sessionId, LoopTurnResult result) {
        LoopTurnResult.Action action = result == null ? LoopTurnResult.Action.NONE : result.action();
        if (result != null && result.hasDirective()) {
            context.getConversationManager().appendInternalUserMessage(sessionId, result.directive());
        }
        if (action == LoopTurnResult.Action.SWITCH_TO_EXECUTE) {
            AgentRequest request = execution.getAgentRequest();
            context.getConversationManager().refreshBoundary(sessionId, LoopBoundary.EXECUTE,
                    request == null ? null : request.getSystemPrompt(),
                    request == null ? null : request.getTask(),
                    request == null ? null : request.getToolList(), context.getWorkspace());
            executeBoundarySelected = true;
        } else if (action == LoopTurnResult.Action.CANCEL) {
            stopped = true;
            execution.cancel();
        } else if (action == LoopTurnResult.Action.STOP) {
            stopped = true;
        }
        return action;
    }

    private AgentLoopHook hook() {
        return context.getAgentLoopHook() == null ? AgentLoopHook.NOOP : context.getAgentLoopHook();
    }
}
