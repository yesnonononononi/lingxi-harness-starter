package com.summit.runtime.sandbox;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads and compares the docker labels that identify a sandbox container.
 *
 * <p>Labels are the only metadata that survives a container restart, so they
 * are used both to prove ownership of a reused container and to rediscover
 * sandboxes after the application store has been lost.</p>
 */
@Slf4j
public final class DockerContainerLabels {

    private static final String LABEL_TEMPLATE =
            "{{range $key, $value := .Config.Labels}}{{$key}}={{$value}}{{println}}{{end}}";

    private DockerContainerLabels() {
    }

    /** All labels of a container, keyed in declaration order. */
    public static Map<String, String> inspect(String containerId) throws IOException {
        String output = DockerCli.output(List.of("docker", "inspect", "-f", LABEL_TEMPLATE, containerId));
        Map<String, String> labels = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                labels.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return Map.copyOf(labels);
    }

    /** {@link #inspect(String)} for callers that fail hard instead of handling an {@link IOException}. */
    public static Map<String, String> inspectOrThrow(String containerId) {
        try {
            return inspect(containerId);
        } catch (IOException e) {
            throw new RuntimeException("failed to inspect docker container labels: " + containerId, e);
        }
    }

    /** True when a container carries {@code key=value} among its labels. */
    public static boolean has(String containerId, String key, String value) {
        try {
            return Objects.equals(value, inspect(containerId).get(key));
        } catch (IOException e) {
            log.debug("could not read the labels of container {}: {}", containerId, e.getMessage());
            return false;
        }
    }

    /**
     * Describes every expected label that is absent or differs on the inspected
     * container, e.g. {@code lingxi.workspace.id expected=a actual=b}.
     */
    public static List<String> mismatched(Map<String, String> actual, Map<String, String> expected) {
        List<String> mismatched = new ArrayList<>();
        expected.forEach((key, value) -> {
            String present = actual.get(key);
            if (!Objects.equals(present, value)) {
                mismatched.add(key + " expected=" + value + " actual=" + (present == null ? "<missing>" : present));
            }
        });
        return List.copyOf(mismatched);
    }

    /**
     * Subset of {@code labels} that proves ownership: when
     * {@code ownershipLabelKeys} is empty every label participates.
     */
    public static Map<String, String> ownership(Map<String, String> labels, Set<String> ownershipLabelKeys) {
        Map<String, String> ownership = new LinkedHashMap<>();
        labels.forEach((key, value) -> {
            if (ownershipLabelKeys == null || ownershipLabelKeys.isEmpty() || ownershipLabelKeys.contains(key)) {
                ownership.put(key, value);
            }
        });
        return Map.copyOf(ownership);
    }
}
