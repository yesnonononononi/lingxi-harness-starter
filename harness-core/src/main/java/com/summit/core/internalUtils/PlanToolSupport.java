package com.summit.core.internalUtils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.summit.core.internalUtils.PlanToolRequests.CompleteTaskRequest;
import com.summit.core.internalUtils.PlanToolRequests.CreatePlanRequest;
import com.summit.core.internalUtils.PlanToolRequests.PlanOperation;
import com.summit.core.internalUtils.PlanToolRequests.TaskDraft;
import com.summit.core.internalUtils.PlanToolRequests.TaskPatch;
import com.summit.core.internalUtils.PlanToolRequests.UpdatePlanRequest;
import com.summit.core.internalUtils.PlanToolRequests.UpdateTaskRequest;
import com.summit.core.plan.Plan;
import com.summit.core.plan.Task;
import com.summit.core.plan.TaskStatus;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolResultType;

import java.util.List;

/**
 * Argument parsing, validation and result rendering shared by every plan kernel tool.
 *
 * <p>Keeping this logic in harness-core means the runtime executors only contain the business
 * step (apply the change to the session plan), while the fragile part — tolerant JSON parsing,
 * field validation, user-facing error wording and optimistic-concurrency messages — is written
 * once and unit-testable without Spring.</p>
 */
public final class PlanToolSupport {

    /** Upper bound of tasks accepted in a single plan; keeps the model output and the card readable. */
    public static final int MAX_TASKS = 30;

    /** Lenient mapper: models routinely add extra keys and use camelCase variants. */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private PlanToolSupport() {
    }

    // ------------------------------------------------------------------ parsing

    public static CreatePlanRequest parseCreate(String args) {
        CreatePlanRequest request = read(args, CreatePlanRequest.class);
        requireText(request.title(), "title");
        if (request.tasks() == null || request.tasks().isEmpty()) {
            throw new PlanArgumentException("tasks must not be empty: a plan needs at least one actionable task");
        }
        if (request.tasks().size() > MAX_TASKS) {
            throw new PlanArgumentException("too many tasks (%d): at most %d tasks are accepted"
                    .formatted(request.tasks().size(), MAX_TASKS));
        }
        for (int i = 0; i < request.tasks().size(); i++) {
            TaskDraft draft = request.tasks().get(i);
            if (draft == null || isBlank(draft.title())) {
                throw new PlanArgumentException("tasks[%d].title is required".formatted(i));
            }
        }
        return request;
    }

    public static UpdatePlanRequest parseUpdatePlan(String args) {
        UpdatePlanRequest request = read(args, UpdatePlanRequest.class);
        requireText(request.planId(), "plan_id");
        if (request.operations() == null || request.operations().isEmpty()) {
            throw new PlanArgumentException("operations must not be empty");
        }
        return request;
    }

    public static UpdateTaskRequest parseUpdateTask(String args) {
        UpdateTaskRequest request = read(args, UpdateTaskRequest.class);
        requireText(request.planId(), "plan_id");
        requireText(request.taskId(), "task_id");
        if (request.patch() == null || request.patch().isEmpty()) {
            throw new PlanArgumentException(
                    "patch must carry at least one of title, description, status, dependencies, priority, acceptance");
        }
        return request;
    }

    public static CompleteTaskRequest parseCompleteTask(String args) {
        CompleteTaskRequest request = read(args, CompleteTaskRequest.class);
        requireText(request.planId(), "plan_id");
        requireText(request.taskId(), "task_id");
        return request;
    }

    /** Deserialises the raw tool arguments, turning every Jackson failure into a model-readable error. */
    public static <T> T read(String args, Class<T> type) {
        if (args == null || args.isBlank()) {
            throw new PlanArgumentException("tool arguments must be a JSON object, but they were empty");
        }
        try {
            return MAPPER.readValue(args, type);
        } catch (Exception e) {
            throw new PlanArgumentException(
                    "tool arguments are not valid JSON for %s: %s".formatted(type.getSimpleName(), e.getMessage()));
        }
    }

    // ------------------------------------------------------- domain application

    /** Normalises a model-provided task draft into a domain {@link Task} (generating a missing id). */
    public static Task taskFromDraft(TaskDraft draft) {
        if (draft == null) {
            throw new PlanArgumentException("task payload must not be null");
        }
        requireText(draft.title(), "task.title");
        return new Task(draft.id(), draft.title(), draft.description(), TaskStatus.parse(draft.status()),
                draft.dependencies(), draft.priority(), draft.acceptance());
    }

    /** Applies every operation of {@code update_plan} in order. */
    public static Plan applyOperations(Plan plan, List<PlanOperation> operations) {
        Plan result = plan;
        for (PlanOperation operation : operations) {
            result = applyOperation(result, operation);
        }
        return result;
    }

    private static Plan applyOperation(Plan plan, PlanOperation operation) {
        if (operation == null || isBlank(operation.op())) {
            throw new PlanArgumentException("every operation needs an op: add_task, update_task, remove_task, update_meta");
        }
        String op = operation.op().trim().toLowerCase();
        return switch (op) {
            case "add_task" -> plan.upsertTask(taskFromDraft(operation.task()));
            case "update_task" -> updateTaskOperation(plan, operation);
            case "remove_task" -> {
                requireTask(plan, operation.taskId(), "remove_task");
                yield plan.removeTask(operation.taskId());
            }
            case "update_meta" -> plan.withMeta(operation.title(), operation.summaryMarkdown());
            default -> throw new PlanArgumentException(
                    "unknown op '%s': valid ops are add_task, update_task, remove_task, update_meta".formatted(operation.op()));
        };
    }

    private static Plan updateTaskOperation(Plan plan, PlanOperation operation) {
        requireText(operation.taskId(), "operations[].task_id for update_task");
        requireTask(plan, operation.taskId(), "update_task");
        if (operation.task() == null) {
            throw new PlanArgumentException("update_task requires a task payload with the fields to change");
        }
        return plan.patchTask(operation.taskId(), task -> applyPatch(task, patchOf(operation.task())));
    }

    /** Converts an {@code add_task} style payload into a field-level patch. */
    public static TaskPatch patchOf(TaskDraft draft) {
        return new TaskPatch(draft.title(), draft.description(), draft.status(),
                draft.dependencies(), draft.priority(), draft.acceptance());
    }

    /** Applies a field-level patch; every {@code null} field keeps the current value. */
    public static Task applyPatch(Task task, TaskPatch patch) {
        Task result = task;
        if (patch.title() != null) {
            result = result.withTitle(requireText(patch.title(), "patch.title"));
        }
        if (patch.description() != null) {
            result = result.withDescription(patch.description());
        }
        if (patch.status() != null) {
            result = result.withStatus(TaskStatus.parse(patch.status()));
        }
        if (patch.dependencies() != null) {
            result = result.withDependencies(patch.dependencies());
        }
        if (patch.priority() != null) {
            result = result.withPriority(patch.priority());
        }
        if (patch.acceptance() != null) {
            result = result.withAcceptance(patch.acceptance());
        }
        return result;
    }

    // ---------------------------------------------------------------- rendering

    /** Successful plan mutation: the output is echoed to the model, the result type marks a plan change. */
    public static ToolExecuteResult ok(ToolExecution execution, String output) {
        return ToolExecuteResult.success(execution.getId(), execution.getToolDefinition(), output,
                ToolResultType.PLAN_UPDATED);
    }

    /** Failed plan mutation; the message is intentionally returned to the model as the tool output. */
    public static ToolExecuteResult error(ToolExecution execution, String message) {
        return ToolExecuteResult.err(execution.getId(), execution.getToolDefinition(), message);
    }

    public static String versionConflict(Plan plan, long expected) {
        return ("version conflict: you sent version %d but plan %s is currently v%d. "
                + "Re-read the plan and retry with the latest version.").formatted(expected, plan.id(), plan.version());
    }

    public static String unknownPlan(String planId) {
        return "unknown plan_id '%s': no plan is registered with that id. Call create_plan first.".formatted(planId);
    }

    public static String noPlanInSession() {
        return "no plan is registered for this session: call create_plan before updating tasks.";
    }

    public static String unknownTask(Plan plan, String taskId) {
        return "unknown task_id '%s'; valid ids of plan %s: %s".formatted(taskId, plan.id(), taskIds(plan));
    }

    public static List<String> taskIds(Plan plan) {
        return plan.tasks().stream().map(Task::id).toList();
    }

    // --------------------------------------------------------------- validation

    public static String requireText(String value, String field) {
        if (isBlank(value)) {
            throw new PlanArgumentException("%s is required and must not be blank".formatted(field));
        }
        return value.trim();
    }

    public static void requireTask(Plan plan, String taskId, String operation) {
        if (!plan.hasTask(taskId)) {
            throw new PlanArgumentException(
                    "%s failed: %s".formatted(operation, unknownTask(plan, String.valueOf(taskId))));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
