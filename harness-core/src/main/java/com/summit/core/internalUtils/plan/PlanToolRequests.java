package com.summit.core.internalUtils.plan;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * Input payloads of the plan kernel tools, deserialised from the model tool-call arguments.
 *
 * <p>Snake-case aliases are accepted everywhere so both {@code summary_markdown} (documented in the
 * schema) and {@code summaryMarkdown} (natural for the model) work.</p>
 */
public final class PlanToolRequests {

    private PlanToolRequests() {
    }

    /** Payload of {@code create_plan}. */
    public record CreatePlanRequest(
            String title,
            @JsonAlias({"summary_markdown", "summaryMarkdown", "summary"}) String summaryMarkdown,
            List<TaskDraft> tasks
    ) {
    }

    /** One task as described by the model, before normalization into a {@code Task}. */
    public record TaskDraft(
            String id,
            String title,
            String description,
            String status,
            List<String> dependencies,
            Integer priority,
            String acceptance
    ) {
    }

    /** Payload of {@code update_plan}. */
    public record UpdatePlanRequest(
            @JsonAlias({"plan_id", "planId", "id"}) String planId,
            Long version,
            List<PlanOperation> operations
    ) {
    }

    /**
     * One structural change of {@code update_plan}.
     *
     * @param op              {@code add_task} / {@code update_task} / {@code remove_task} / {@code update_meta}
     * @param taskId          target task for {@code update_task} / {@code remove_task}
     * @param task            task payload of {@code add_task}
     * @param title           new plan title of {@code update_meta}
     * @param summaryMarkdown new plan document of {@code update_meta}
     */
    public record PlanOperation(
            String op,
            @JsonAlias({"task_id", "taskId", "id"}) String taskId,
            TaskDraft task,
            String title,
            @JsonAlias({"summary_markdown", "summaryMarkdown"}) String summaryMarkdown
    ) {
    }

    /** Payload of {@code update_task}. */
    public record UpdateTaskRequest(
            @JsonAlias({"plan_id", "planId", "id"}) String planId,
            @JsonAlias({"task_id", "taskId"}) String taskId,
            Long version,
            TaskPatch patch
    ) {
    }

    /**
     * Field-level patch of one task; every {@code null} field means "leave unchanged".
     *
     * <p>{@code tips} is the human-side addition: it is not part of the model tool schema, it only
     * reaches the task through the user-facing patch endpoint.</p>
     */
    public record TaskPatch(
            String title,
            String description,
            String status,
            List<String> dependencies,
            Integer priority,
            String acceptance,
            String tips
    ) {
        /** Whether the patch actually carries at least one change. */
        public boolean isEmpty() {
            return title == null && description == null && status == null
                    && dependencies == null && priority == null && acceptance == null
                    && tips == null;
        }
    }

    /** Payload of {@code complete_task}. */
    public record CompleteTaskRequest(
            @JsonAlias({"plan_id", "planId", "id"}) String planId,
            @JsonAlias({"task_id", "taskId"}) String taskId,
            Long version
    ) {
    }
}
