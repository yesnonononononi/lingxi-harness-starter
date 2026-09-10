package com.summit.core.plan;

import java.util.Comparator;
import java.util.List;

/**
 * Single rendering path for a {@link Plan} as text: used by the runtime when it injects the plan
 * into the model conversation, by the context compacters when they protect the plan from being
 * squeezed away, and by the kernel tools when they echo the resulting plan back to the model.
 *
 * <p>Keeping the three call sites on one formatter avoids the drift the previous implementation
 * suffered (plan text rendered differently in the prompt, the tool result and the compressor).</p>
 */
public final class PlanOutline {

    private PlanOutline() {
    }

    /** Renders the whole plan: title, meta line, Markdown document and every task. */
    public static String render(Plan plan) {
        if (plan == null) {
            return "(no plan)";
        }
        StringBuilder outline = new StringBuilder();
        outline.append("# ").append(plan.title().isBlank() ? "(untitled plan)" : plan.title()).append('\n');
        outline.append("planId: ").append(plan.id())
                .append(" · version: v").append(plan.version())
                .append(" · status: ").append(plan.status().getLabel())
                .append(" · tasks: ").append(plan.doneTaskCount()).append('/').append(plan.tasks().size())
                .append(" done\n");
        if (!plan.summaryMarkdown().isBlank()) {
            outline.append('\n').append(plan.summaryMarkdown()).append('\n');
        }
        if (!plan.tasks().isEmpty()) {
            outline.append("\n## Tasks\n");
            appendTasks(outline, plan.tasks());
        }
        return outline.toString().strip();
    }

    /** Renders only the tasks that are still open (used by the "you may not finish yet" reminder). */
    public static String renderOpenTasks(Plan plan) {
        if (plan == null) {
            return "(no plan)";
        }
        List<Task> open = plan.tasks().stream().filter(task -> !task.isDone()).toList();
        if (open.isEmpty()) {
            return "(every task is done)";
        }
        StringBuilder outline = new StringBuilder();
        appendTasks(outline, open);
        return outline.toString().strip();
    }

    /** Short "2/5" style progress string of the plan. */
    public static String progress(Plan plan) {
        if (plan == null || plan.tasks().isEmpty()) {
            return "0/0";
        }
        return plan.doneTaskCount() + "/" + plan.tasks().size();
    }

    private static void appendTasks(StringBuilder outline, List<Task> tasks) {
        List<Task> ordered = tasks.stream()
                .sorted(Comparator.comparingInt(task -> task.priority() == null ? Integer.MAX_VALUE : task.priority()))
                .toList();
        for (Task task : ordered) {
            outline.append("- [").append(task.id()).append("] ").append(task.title())
                    .append(" (").append(task.status().name().toLowerCase());
            if (task.priority() != null) {
                outline.append(", p").append(task.priority());
            }
            outline.append(')');
            if (!task.dependencies().isEmpty()) {
                outline.append(" depends on: ").append(String.join(", ", task.dependencies()));
            }
            outline.append('\n');
            if (task.description() != null) {
                outline.append("    description: ").append(task.description().replace("\n", "\n    ")).append('\n');
            }
            if (task.acceptance() != null) {
                outline.append("    acceptance: ").append(task.acceptance().replace("\n", "\n    ")).append('\n');
            }
        }
    }
}
