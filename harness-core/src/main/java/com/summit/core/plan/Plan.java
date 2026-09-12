package com.summit.core.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * A plan produced by the {@code create_plan} kernel tool and stored per session by
 * {@link PlanStore}.
 *
 * <p>Deliberately self-contained: a plan knows nothing about sessions, executions or HTTP. The
 * session binding lives in the store, so the entity can be serialised to the front-end verbatim.</p>
 *
 * <p>Immutable record: every mutating method returns a new plan with {@code version + 1} and a
 * refreshed {@code updatedAt}. The revision counter is the optimistic-concurrency token carried by
 * {@code update_plan} / {@code update_task}, so two concurrent writers can never silently
 * overwrite each other.</p>
 *
 * @param id              unique plan identifier (UUID)
 * @param version         revision counter, incremented by every mutation (never below 1)
 * @param title           one-line plan title
 * @param summaryMarkdown the Markdown plan document (context, approach, risks)
 * @param tasks           structured task list (never {@code null})
 * @param status          plan-level state machine, {@link PlanStatus#DRAFT} when newly created
 * @param createdAt       creation time
 * @param updatedAt       last mutation time
 */
public record Plan(
        String id,
        long version,
        String title,
        String summaryMarkdown,
        List<Task> tasks,
        PlanStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public Plan {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        version = Math.max(version, 1L);
        title = title == null ? "" : title.trim();
        summaryMarkdown = summaryMarkdown == null ? "" : summaryMarkdown;
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        status = status == null ? PlanStatus.DRAFT : status;
        Instant now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = updatedAt == null ? now : updatedAt;
    }

    /** Creates a brand-new draft plan (version 1, {@link PlanStatus#DRAFT}). */
    public static Plan create(String title, String summaryMarkdown, List<Task> tasks) {
        return new Plan(null, 1L, title, summaryMarkdown, tasks, PlanStatus.DRAFT, null, null);
    }

    /** Replaces the human-readable plan meta (title / Markdown document). */
    public Plan withMeta(String title, String summaryMarkdown) {
        return new Plan(id, version + 1, title == null ? this.title : title,
                summaryMarkdown == null ? this.summaryMarkdown : summaryMarkdown,
                tasks, status, createdAt, null);
    }

    /** Transitions the plan-level state machine. */
    public Plan withStatus(PlanStatus status) {
        return new Plan(id, version + 1, title, summaryMarkdown, tasks, status, createdAt, null);
    }

    /** Adds the task, or replaces the existing task carrying the same id (order preserved). */
    public Plan upsertTask(Task task) {
        if (task == null) {
            return this;
        }
        List<Task> updated = new ArrayList<>(tasks);
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).id().equals(task.id())) {
                // A model-driven rewrite must never silently drop the hints the user attached
                // to the step, so a task without tips inherits the ones already stored.
                Task previous = updated.get(i);
                updated.set(i, task.tips() == null ? task.withTips(previous.tips()) : task);
                return withTasks(updated);
            }
        }
        updated.add(task);
        return withTasks(updated);
    }

    /** Removes a task by id; returns {@code this} when the id is unknown. */
    public Plan removeTask(String taskId) {
        if (taskId == null) {
            return this;
        }
        List<Task> updated = tasks.stream().filter(task -> !task.id().equals(taskId)).toList();
        return updated.size() == tasks.size() ? this : withTasks(updated);
    }

    /**
     * Applies a field-level mutation to one task.
     *
     * @return the updated plan; {@code this} when the task id is unknown or the mutation is a no-op
     */
    public Plan patchTask(String taskId, UnaryOperator<Task> mutation) {
        if (taskId == null || mutation == null) {
            return this;
        }
        List<Task> updated = new ArrayList<>(tasks);
        boolean matched = false;
        for (int i = 0; i < updated.size(); i++) {
            Task current = updated.get(i);
            if (!current.id().equals(taskId)) {
                continue;
            }
            matched = true;
            Task patched = mutation.apply(current);
            if (patched == null || patched.equals(current)) {
                return this;
            }
            updated.set(i, patched);
        }
        return matched ? withTasks(updated) : this;
    }

    /** Finds a task by id. */
    public Optional<Task> taskOf(String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return tasks.stream().filter(task -> task.id().equals(taskId)).findFirst();
    }

    /** Whether the plan holds a task with the given id. */
    public boolean hasTask(String taskId) {
        return taskOf(taskId).isPresent();
    }

    /** Ids of every task that is not {@link TaskStatus#DONE} yet (order preserved). */
    public List<String> openTaskIds() {
        return tasks.stream().filter(task -> !task.isDone()).map(Task::id).toList();
    }

    /** Number of tasks already done. */
    public long doneTaskCount() {
        return tasks.stream().filter(Task::isDone).count();
    }

    /** Whether the plan has at least one task and every task is done. */
    public boolean allTasksDone() {
        return !tasks.isEmpty() && tasks.stream().allMatch(Task::isDone);
    }

    private Plan withTasks(List<Task> newTasks) {
        return new Plan(id, version + 1, title, summaryMarkdown, List.copyOf(newTasks), status,
                createdAt, null);
    }
}
