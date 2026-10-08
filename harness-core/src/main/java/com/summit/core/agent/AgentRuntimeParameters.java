package com.summit.core.agent;

import com.summit.core.conversation.event.AgentEvent;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/** Per-run controls for an agent request. */
@Data
@Builder
@Jacksonized
public class AgentRuntimeParameters {

    /** Opaque per-run attributes for application policies. The runtime only carries them and never inspects a key. */
    @Builder.Default
    private Map<String, Object> attributes = Map.of();


    /**
     *  the field can be transported with the {@link AgentEvent}
     */
    @Builder.Default
    private Map<String,Object> eventMetaData = Map.of();

    public AgentRuntimeParameters(Map<String, Object> attributes, Map<String, Object> eventMetaData,
                                  boolean allowOutsideWorkspace) {
        this.attributes = attributes;
        this.eventMetaData = eventMetaData == null ? Map.of() : Map.copyOf(eventMetaData);
        this.allowOutsideWorkspace = allowOutsideWorkspace;
    }
    public Map<String, Object> getEventMetaData() {
        return eventMetaData == null ? Map.of() : Map.copyOf(eventMetaData);
    }

    public void setEventMetaData(Map<String, Object> eventMetaData) {
        this.eventMetaData = eventMetaData == null ? Map.of() : Map.copyOf(eventMetaData);
    }

    /** Whether this run may operate outside the workspace root (read or write paths that {@code Workspace#encloses(Path)} reports as outside). */
    @Builder.Default
    private boolean allowOutsideWorkspace = false;


}
