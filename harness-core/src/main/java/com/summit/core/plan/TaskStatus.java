package com.summit.core.plan;

/**
 * Lifecycle status of a single {@link Task} of a {@link Plan}.
 *
 * <p>Newly planned tasks start as {@link #TODO}. The agent moves them to {@link #DOING} while it
 * implements them, may flag {@link #BLOCKED} when it cannot proceed, and closes them with
 * {@link #DONE} (normally through {@code complete_task}).</p>
 */
public enum TaskStatus {

    TODO("待开始"),
    DOING("进行中"),
    BLOCKED("受阻"),
    DONE("已完成");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Whether this task is finished and therefore no longer counts as pending work. */
    public boolean isDone() {
        return this == DONE;
    }

    /** Tolerant parse used when a status arrives from JSON; falls back to {@link #TODO}. */
    public static TaskStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return TODO;
        }
        return switch (raw.trim().toLowerCase()) {
            case "doing", "in_progress", "inprogress", "running", "active" -> DOING;
            case "blocked", "stuck", "waiting" -> BLOCKED;
            case "done", "completed", "complete", "finished" -> DONE;
            default -> TODO;
        };
    }
}
