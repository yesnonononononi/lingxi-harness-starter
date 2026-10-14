package com.summit.sandbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.WorkspaceSpec;

import java.util.Map;

/** Creates built-in specs that need no additional JSON subtype registration for snapshot recovery. */
public final class SandboxWorkspaceSpec {
    private static final String POLICY = "sandbox.policy";
    private static final ObjectMapper JSON = new ObjectMapper();

    private SandboxWorkspaceSpec() {}

    public static BasicWorkspaceSpec of(String workDir, SandboxPolicy policy) {
        return of(workDir, "", policy);
    }

    public static BasicWorkspaceSpec of(String workDir, String scope, SandboxPolicy policy) {
        try {
            return new BasicWorkspaceSpec(SandboxWorkspaceProvider.TYPE, workDir, scope,
                    Map.of(POLICY, JSON.writeValueAsString(policy == null ? SandboxPolicy.builder().build() : policy)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode sandbox policy", e);
        }
    }

    static SandboxPolicy policy(WorkspaceSpec spec) throws JsonProcessingException {
        String encoded = spec.configuration().get(POLICY);
        return encoded == null ? SandboxPolicy.builder().build() : JSON.readValue(encoded, SandboxPolicy.class);
    }
}
