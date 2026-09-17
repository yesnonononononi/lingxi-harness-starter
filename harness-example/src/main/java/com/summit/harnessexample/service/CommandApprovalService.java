package com.summit.harnessexample.service;

import com.summit.core.runtime.LoopSuspender;
import com.summit.core.runtime.SuspensionDecision;
import com.summit.harnessexample.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records the human decision on a command that is waiting for approval.
 *
 * <p>This is the <b>business</b> half of command approval: the framework only provides the generic
 * suspension SPI ({@link LoopSuspender}) plus the admission point
 * ({@code com.summit.core.tool.ToolExecutionPolicy}); deciding <em>which</em> commands need
 * approval, what the card shows and how the decision is keyed is owned here.</p>
 *
 * <p>Resuming the suspension wakes the agent-loop thread that is blocked before the command's own
 * execution timeout has even started. An unknown or already-decided command is reported as a
 * not-found / conflict so the front-end can drop its stale approval card.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandApprovalService {

    private final LoopSuspender suspender;

    /**
     * Resumes the suspension of one command approval.
     *
     * @param toolExecutionId id of the suspended tool call, which is also the suspension id
     * @param approved        whether the user lets the command run
     */
    public Map<String, Object> decide(String toolExecutionId, boolean approved) {
        if (toolExecutionId == null || toolExecutionId.isBlank()) {
            throw ApiException.badRequest("toolExecutionId must not be blank");
        }
        LoopSuspender.Suspension pending = suspender.find(toolExecutionId)
                .orElseThrow(() -> ApiException.notFound(
                        "no pending command found for toolExecutionId: " + toolExecutionId,
                        Map.of("toolExecutionId", toolExecutionId)));
        String command = String.valueOf(pending.request().payload().getOrDefault("command", ""));

        boolean applied = suspender.resolve(toolExecutionId,
                SuspensionDecision.resume(Map.of("approved", approved)));
        if (!applied) {
            throw ApiException.conflict("command decision failed, it may have already been decided",
                    Map.of("toolExecutionId", toolExecutionId));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolExecutionId", toolExecutionId);
        data.put("command", command);
        data.put("approved", approved);
        log.info("【command-decision】approved={} command: toolExecutionId={}, command={}",
                approved, toolExecutionId, command);
        return data;
    }
}
