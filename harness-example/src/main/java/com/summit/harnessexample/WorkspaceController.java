package com.summit.harnessexample;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP endpoints behind the front-end "select workspace folder" picker.
 *
 * <ul>
 *   <li>{@code GET /agent/workspace/dirs} — browse one level of a host folder
 *       (sub-directories only, no file contents) or list drive roots when no
 *       path is given.</li>
 *   <li>{@code POST /agent/workspace/select} — make the given host folder the
 *       active sandbox (reuse the container mounting it or create a new one).</li>
 *   <li>{@code GET /agent/workspace/current} — current workspace state.</li>
 * </ul>
 *
 * <p>Responses follow the shared {@code {code, message, data}} envelope with
 * {@code code=200} on success.</p>
 */
@Slf4j
@RestController
@RequestMapping("/agent/workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    /** Safety cap for one directory listing (folders are cheap, unbounded lists are not). */
    private static final int MAX_DIRS = 500;

    private final WorkspaceSandboxService workspaceSandboxService;

    /**
     * Lists the immediate sub-directories of {@code path} for the folder picker.
     * With an absent or blank path the drive roots of the host are returned.
     */
    @GetMapping("/dirs")
    public ResponseEntity<Map<String, Object>> dirs(@RequestParam(value = "path", required = false) String path) {
        try {
            return ok(browse(path));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IOException e) {
            log.warn("failed to browse directory '{}': {}", path, e.getMessage());
            return serverError("failed to read directory: " + e.getMessage());
        }
    }

    /**
     * Selects a host folder as the active workspace. In docker mode an existing
     * container mounting the folder is reused when present, otherwise a new
     * container is created with the folder bind-mounted. Switches globally.
     */
    @PostMapping("/select")
    public ResponseEntity<Map<String, Object>> select(@RequestBody Map<String, String> body) {
        String path = body == null ? null : body.get("path");
        try {
            Map<String, Object> data = workspaceSandboxService.select(path);
            return ok(data);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("failed to select workspace '{}': {}", path, e.getMessage());
            return serverError(e.getMessage() == null ? "workspace switch failed" : e.getMessage());
        }
    }

    /** Returns the current active workspace state (host folder, container, workdir, mode). */
    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> current() {
        return ok(workspaceSandboxService.current());
    }

    // ------------------------------------------------------------------
    // host directory browsing
    // ------------------------------------------------------------------

    private Map<String, Object> browse(String path) throws IOException {
        List<Map<String, String>> directories = new ArrayList<>();

        if (path == null || path.isBlank()) {
            // Drive-roots view (C:\, D:\ on Windows, / on Linux/macOS).
            for (File root : File.listRoots()) {
                String rootPath = root.getPath();
                directories.add(entry(rootPath, rootPath));
            }
            directories.sort(Comparator.comparing(e -> e.get("name").toLowerCase(Locale.ROOT)));
            return response("", null, true, directories);
        }

        Path requested = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(requested)) {
            throw new IllegalArgumentException("directory does not exist or is not accessible: " + requested);
        }

        String displayPath = requested.toString();
        Path filesystemRoot = requested.getRoot();
        boolean isRoot = filesystemRoot != null && requested.equals(filesystemRoot);
        Path parentPath = requested.getParent();
        String parent = parentPath == null ? null : parentPath.toString();

        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(requested)) {
            for (Path child : stream) {
                if (count >= MAX_DIRS) {
                    break;
                }
                try {
                    if (!Files.isDirectory(child)) {
                        continue;
                    }
                } catch (Exception unreadable) {
                    // Skip entries we cannot even stat; the user can still pick other folders.
                    continue;
                }
                String childPath = child.toString();
                String name = child.getFileName() == null ? childPath : child.getFileName().toString();
                directories.add(entry(name, childPath));
                count++;
            }
        }

        directories.sort(Comparator.comparing(e -> e.get("name").toLowerCase(Locale.ROOT)));
        return response(displayPath, parent, isRoot, directories);
    }

    private static Map<String, String> entry(String name, String path) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("path", path);
        return entry;
    }

    private static Map<String, Object> response(String path, String parent, boolean isRoot,
                                                List<Map<String, String>> directories) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        data.put("parent", parent);
        data.put("isRoot", isRoot);
        data.put("directories", directories);
        return data;
    }

    // ------------------------------------------------------------------
    // response envelope helpers (aligned with the existing controllers)
    // ------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 400);
        response.put("message", message == null ? "bad request" : message);
        return ResponseEntity.badRequest().body(response);
    }

    private ResponseEntity<Map<String, Object>> serverError(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 500);
        response.put("message", message == null ? "internal error" : message);
        return ResponseEntity.internalServerError().body(response);
    }
}
