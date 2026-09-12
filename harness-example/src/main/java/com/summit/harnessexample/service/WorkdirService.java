package com.summit.harnessexample.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.Workspace;
import com.summit.harnessexample.ActiveWorkspace;
import com.summit.harnessexample.LocalWorkSpace;
import com.summit.harnessexample.SseEventPublisher;
import com.summit.harnessexample.common.ApiException;
import com.summit.runtime.sandbox.DockerWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and switches the agent's working directory.
 *
 * <p>The switch is workspace-type aware (in-container path for the Docker sandbox,
 * existing host folder for the local workspace) and is broadcast to every connected
 * SSE client so all open pages stay in sync.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkdirService {

    private final ActiveWorkspace activeWorkspace;
    private final SseEventPublisher sseEventPublisher;
    private final ObjectMapper objectMapper;

    /** The agent's current working directory. */
    public Map<String, Object> current() {
        return Map.of("workdir", activeWorkspace.get().workDir());
    }

    /** Switches the working directory and notifies all clients. */
    public Map<String, Object> update(String workdir) {
        if (workdir == null || workdir.isBlank()) {
            throw ApiException.badRequest("workdir must not be blank");
        }

        Workspace current = activeWorkspace.get();
        String kind = current instanceof DockerWorkspace ? "docker" : "local";
        String previous = current.workDir();
        try {
            if (current instanceof DockerWorkspace docker) {
                // Sandbox workspace: switch the in-container working root (the directory need
                // not exist yet — the agent can create it itself via edit_file / mkdir).
                docker.setWorkspaceRoot(normalizeContainerPath(workdir));
            } else if (current instanceof LocalWorkSpace local) {
                // Local workspace: switch to an existing host directory.
                local.updateWorkDir(workdir);
            } else {
                throw new IllegalArgumentException("workspace does not support switching workdir");
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }

        String workdirNow = activeWorkspace.get().workDir();
        // 只在 workspace 对象内部改 workDir，不换 workspace（id/容器/mode 都不变），
        // 这里显式记录一行，便于从日志确认改动是否真的生效。
        log.info("workdir switched ({}): {} -> {}", kind, previous, workdirNow);
        broadcast(workdirNow);
        return Map.of("workdir", workdirNow);
    }

    /**
     * Normalizes a user-supplied in-container path to an absolute POSIX path
     * ({@code "/app"}, {@code "app"} -&gt; {@code "/app"}).
     */
    private String normalizeContainerPath(String workdir) {
        String p = workdir.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.matches("/[A-Za-z0-9._\\-/]+")) {
            throw new IllegalArgumentException("invalid container path: " + workdir);
        }
        return p;
    }

    private void broadcast(String workdir) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "WORKDIR_CHANGED");
        event.put("data", Map.of("workdir", workdir));
        try {
            sseEventPublisher.broadcast(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.warn("Failed to broadcast workdir change", e);
        }
    }
}
