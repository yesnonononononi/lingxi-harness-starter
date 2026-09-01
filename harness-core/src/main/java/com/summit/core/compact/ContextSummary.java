package com.summit.core.compact;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class ContextSummary {
    /**
     * Goal of the context
     */
    private String goal;
    /**
     * Summary of the context
     */
    private String summary;
    /**
     * Completed tasks
     */
    private List<String> completed;
    /**
     * Pending tasks
     */
    private List<String> pending;
    /**
     * status DONE FAILED
     */
    private String state;
}
