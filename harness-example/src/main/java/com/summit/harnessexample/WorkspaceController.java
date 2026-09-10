package com.summit.harnessexample;

import com.summit.harnessexample.common.Result;
import com.summit.harnessexample.dto.WorkspaceSelectRequest;
import com.summit.harnessexample.service.HostDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HTTP endpoints behind the front-end "select workspace folder" picker.
 *
 * <ul>
 *   <li>{@code GET /agent/workspace/dirs} — browse one level of a host folder
 *       (sub-directories only) or list drive roots when no path is given.</li>
 *   <li>{@code POST /agent/workspace/select} — make the given host folder the
 *       active workspace (reuse the container mounting it or create a new one).</li>
 *   <li>{@code GET /agent/workspace/current} — current workspace state.</li>
 * </ul>
 */
@RestController
@RequestMapping("/agent/workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceSandboxService workspaceSandboxService;
    private final HostDirectoryService hostDirectoryService;

    /** Lists the immediate sub-directories of {@code path}; blank path lists the drive roots. */
    @GetMapping("/dirs")
    public Result<Map<String, Object>> dirs(@RequestParam(value = "path", required = false) String path) {
        return Result.ok(hostDirectoryService.browse(path));
    }

    /** Selects a host folder as the active workspace (switches globally). */
    @PostMapping("/select")
    public Result<Map<String, Object>> select(@RequestBody(required = false) WorkspaceSelectRequest request) {
        return Result.ok(workspaceSandboxService.select(request == null ? null : request.path()));
    }

    /** Returns the current active workspace state (host folder, container, workdir, mode). */
    @GetMapping("/current")
    public Result<Map<String, Object>> current() {
        return Result.ok(workspaceSandboxService.current());
    }
}
