package com.summit.harnessexample.service;

import com.summit.harnessexample.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 * Browses host directories one level at a time for the front-end folder picker
 * (sub-directories only, no file contents).
 */
@Slf4j
@Service
public class HostDirectoryService {

    /** Safety cap for one listing (folders are cheap, unbounded lists are not). */
    private static final int MAX_DIRS = 500;

    /**
     * Lists the immediate sub-directories of {@code path}. With an absent or blank path
     * the drive roots of the host are returned.
     */
    public Map<String, Object> browse(String path) {
        if (path == null || path.isBlank()) {
            return driveRoots();
        }

        Path requested;
        try {
            requested = Paths.get(path).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid path: " + path);
        }
        if (!Files.isDirectory(requested)) {
            throw ApiException.badRequest("directory does not exist or is not accessible: " + requested);
        }

        String displayPath = requested.toString();
        Path filesystemRoot = requested.getRoot();
        boolean isRoot = filesystemRoot != null && requested.equals(filesystemRoot);
        Path parentPath = requested.getParent();
        String parent = parentPath == null ? null : parentPath.toString();

        List<Map<String, String>> directories = new ArrayList<>();
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
                } catch (RuntimeException unreadable) {
                    // Skip entries we cannot even stat; the user can still pick other folders.
                    continue;
                }
                String childPath = child.toString();
                String name = child.getFileName() == null ? childPath : child.getFileName().toString();
                directories.add(entry(name, childPath));
                count++;
            }
        } catch (IOException e) {
            log.warn("failed to browse directory '{}': {}", path, e.getMessage());
            throw ApiException.internalError("failed to read directory: " + e.getMessage());
        }

        sort(directories);
        return response(displayPath, parent, isRoot, directories);
    }

    private Map<String, Object> driveRoots() {
        List<Map<String, String>> directories = new ArrayList<>();
        for (File root : File.listRoots()) {
            String rootPath = root.getPath();
            directories.add(entry(rootPath, rootPath));
        }
        sort(directories);
        return response("", null, true, directories);
    }

    private void sort(List<Map<String, String>> directories) {
        directories.sort(Comparator.comparing(e -> e.get("name").toLowerCase(Locale.ROOT)));
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
}
