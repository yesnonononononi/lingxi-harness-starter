package com.summit.core.json;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.WorkspaceSpec;

/** JSON mapper factory for execution snapshots. Configure once and reuse the mapper. */
public final class ExecutionJson {
    private ExecutionJson() {}

    /**
     * Supports built-in messages, time values and BasicWorkspaceSpec. Register other workspace
     * implementations with stable names, e.g. new NamedType(DockerWorkspaceSpec.class, "docker").
     * Runtime attributes must contain JSON-compatible values; arbitrary Java object identity is
     * not preserved. Snapshots include request model credentials and belong in trusted storage.
     * No global default typing is enabled.
     *
     * @param workspaceTypes additional workspace implementations accepted when restoring snapshots
     * @return a new mapper to configure before its first use
     */
    public static ObjectMapper newObjectMapper(NamedType... workspaceTypes) {
        for (NamedType type : workspaceTypes) {
            if (!WorkspaceSpec.class.isAssignableFrom(type.getType()) || !type.hasName()) {
                throw new IllegalArgumentException("Workspace subtypes must implement WorkspaceSpec and have a name");
            }
        }
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .addMixIn(WorkspaceSpec.class, WorkspaceType.class)
                .build();
        mapper.registerSubtypes(workspaceTypes);
        return mapper;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "workspaceType")
    @JsonSubTypes(@JsonSubTypes.Type(value = BasicWorkspaceSpec.class, name = "basic"))
    private interface WorkspaceType {}
}
