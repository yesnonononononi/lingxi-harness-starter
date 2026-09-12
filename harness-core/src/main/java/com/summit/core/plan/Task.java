package com.summit.core.plan;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A single actionable task of a {@link Plan}.
 *
 * <p>The task list is the structured, machine-verifiable part of a plan: the {@code title} is the
 * short imperative summary, {@code description} carries the implementation detail and
 * {@code acceptance} carries the explicit criterion the agent (and the user) judge the task
 * against. {@code dependencies} refer to other task ids of the same plan, and {@code priority}
 * orders the task list (lower value = higher priority, {@code null} = unspecified).</p>
 *
 * <p>{@code tips} is the human-owned field: extra guidance the user attaches to this step while
 * reviewing the plan (for example "run the build before considering this done"). It never rewrites
 * what the model proposed — {@link PlanOutline} renders it next to the task, so the agent reads it
 * together with the approved plan and has to honour it.</p>
 *
 * <p>Immutable record: every {@code withX} call returns a new task, so plan mutations stay
 * copy-on-write and thread-safe.</p>
 *
 * @param id           unique task identifier inside the plan
 * @param title        short imperative title (never {@code null})
 * @param description  optional implementation detail
 * @param status       lifecycle status, {@link TaskStatus#TODO} when absent
 * @param dependencies ids of the tasks that must be done first (never {@code null})
 * @param priority     optional ordering hint, lower first
 * @param acceptance   optional explicit acceptance criterion
 * @param tips         optional human-provided hint attached to this step (never set by the model)
 */
public record Task(
        String id,
        String title,
        String description,
        TaskStatus status,
        List<String> dependencies,
        Integer priority,
        String acceptance,
        String tips
) {

    public Task {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        title = title == null ? "" : title.trim();
        description = trimToNull(description);
        status = status == null ? TaskStatus.TODO : status;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(dependency -> !dependency.isEmpty())
                .toList());
        acceptance = trimToNull(acceptance);
        tips = trimToNull(tips);
    }

    /** Creates a task with a generated id, used when the model omits the id. */
    public static Task create(String title, String description, List<String> dependencies,
                              Integer priority, String acceptance) {
        return new Task(null, title, description, TaskStatus.TODO, dependencies, priority, acceptance, null);
    }

    public Task withTitle(String title) {
        return new Task(id, title, description, status, dependencies, priority, acceptance, tips);
    }

    public Task withDescription(String description) {
        return new Task(id, title, description, status, dependencies, priority, acceptance, tips);
    }

    public Task withStatus(TaskStatus status) {
        return new Task(id, title, description, status, dependencies, priority, acceptance, tips);
    }

    public Task withDependencies(List<String> dependencies) {
        return new Task(id, title, description, status, dependencies, priority, acceptance, tips);
    }

    public Task withPriority(Integer priority) {
        return new Task(id, title, description, status, dependencies, priority, acceptance, tips);
    }

    public Task withAcceptance(String acceptance) {
        return new Task(id, title, description, status, dependencies, priority, acceptance, tips);
    }

    /** Attaches / replaces / clears ({@code null} or blank) the human-provided hint of this step. */
    public Task withTips(String tips) {
        return new Task(id, title, description, status, dependencies, priority, acceptance, tips);
    }

    /** Whether this task is already closed. */
    public boolean isDone() {
        return status != null && status.isDone();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
