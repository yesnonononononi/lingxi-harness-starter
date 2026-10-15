package com.summit.sandbox.mxc;

import org.junit.jupiter.api.Test;
import com.summit.core.json.ExecutionJson;
import com.summit.core.workspace.WorkspaceSpec;
import com.fasterxml.jackson.databind.jsontype.NamedType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MxcRuntimeTest {
    @Test void mapsDrivesAndOnlyTheSelectedDistributionWithoutShellExpansion() {
        assertEquals("/mnt/d/Code/a b/'$x&中文", MxcRuntime.windowsToLinux("D:\\Code\\a b\\'$x&中文", "Ubuntu"));
        assertEquals("/home/me/work", MxcRuntime.windowsToLinux("\\\\wsl.localhost\\Ubuntu\\home\\me\\work", "Ubuntu"));
        assertEquals("/home/me/work", MxcRuntime.windowsToLinux("\\\\wsl$\\ubuntu\\home\\me\\work", "Ubuntu"));
        assertThrows(IllegalArgumentException.class, () -> MxcRuntime.windowsToLinux("\\\\server\\share\\a", "Ubuntu"));
        assertThrows(IllegalArgumentException.class, () -> MxcRuntime.windowsToLinux("\\\\wsl$\\Debian\\a", "Ubuntu"));
        assertThrows(IllegalArgumentException.class, () -> MxcRuntime.windowsToLinux("D:relative", "Ubuntu"));
    }

    @Test void normalizesLinuxPathsAndRejectsAmbiguousInputs() {
        assertEquals("/a/c", MxcRuntime.linuxAbsolute("/a//b/../c/./"));
        assertEquals("/c", MxcRuntime.linuxAbsolute("/../../c"));
        for (String invalid : List.of("relative", "//server/a", "/a\\b", "/a\ncommand", "/a\0b")) {
            assertThrows(IllegalArgumentException.class, () -> MxcRuntime.linuxAbsolute(invalid));
        }
    }

    @Test void copiesPolicyInputsAndRequiresExplicitEnvironmentKeys() {
        assertThrows(IllegalArgumentException.class, () -> new MxcWorkspaceSpec(" "));
        assertThrows(IllegalArgumentException.class, () -> new MxcWorkspaceSpec("/work", "", List.of(), List.of(), Map.of("X=bad", "value")));
        assertThrows(UnsupportedOperationException.class, () -> new MxcWorkspaceSpec("/work").readwritePaths().add("/"));
    }

    @Test void restoresTypedPoliciesThroughTheExecutionSnapshotMapper() throws Exception {
        MxcWorkspaceSpec spec = new MxcWorkspaceSpec("/work", "tenant", List.of("/tools"), List.of("/cache"), Map.of("LANG", "C.UTF-8"));
        com.fasterxml.jackson.databind.ObjectMapper mapper = ExecutionJson.newObjectMapper(new NamedType(MxcWorkspaceSpec.class, "mxc"));
        String json = mapper.writerFor(WorkspaceSpec.class).writeValueAsString(spec);
        assertEquals(spec, mapper.readValue(json, WorkspaceSpec.class));
    }
}
