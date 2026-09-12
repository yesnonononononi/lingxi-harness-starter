package com.summit.core.internalUtils.plan;

/**
 * Single source of truth for the plan kernel tool contract: name, description and JSON parameter
 * schema.
 *
 * <p>The descriptions carry the plan-mode behavioural contract that used to be injected into the
 * PLANING system prompt, so the model learns how to plan from the tool definition itself — one
 * contract, one place, no prompt/tool drift.</p>
 */
public final class PlanToolSpecs {

    /** Immutable tool contract slice, consumed by the autoconfiguration when registering. */
    public record PlanToolSpec(String name, String description, String parametersJsonSchema) {
    }

    public static final PlanToolSpec CREATE_PLAN = new PlanToolSpec(
            PlanToolNames.CREATE_PLAN,
            """
            Register the implementation plan of the current request and hand it over to the user for approval.
            Call it as soon as you know HOW the request will be done, before modifying any file.
            Produce a one-line title, an optional Markdown plan document, and 1-12 actionable tasks.
            Every task needs a short imperative title and an explicit, verifiable acceptance criterion.
            Right after this call the runtime suspends you and waits for the user's decision:
            do not attempt any write action until the plan has been approved.""",
            """
            {
              "type": "object",
              "properties": {
                "title": {"type": "string", "description": "One-line plan title."},
                "summary_markdown": {"type": "string", "description": "Full Markdown plan document: context, approach, risks."},
                "tasks": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string", "description": "Optional stable id such as task-1; generated when omitted."},
                      "title": {"type": "string", "description": "Short imperative title of the task."},
                      "description": {"type": "string", "description": "Implementation detail of the task."},
                      "status": {"type": "string", "enum": ["todo", "doing", "blocked", "done"]},
                      "dependencies": {"type": "array", "items": {"type": "string"}, "description": "Ids of tasks that must be done first."},
                      "priority": {"type": "integer", "description": "Ordering hint, lower runs first."},
                      "acceptance": {"type": "string", "description": "Explicit acceptance criterion used to judge the task."}
                    },
                    "required": ["title"]
                  }
                }
              },
              "required": ["title", "tasks"]
            }""");

    public static final PlanToolSpec UPDATE_PLAN = new PlanToolSpec(
            PlanToolNames.UPDATE_PLAN,
            """
            Apply a batch of structural changes to an existing plan (add / update / remove tasks, or
            rewrite the plan meta). Always pass the `version` of the plan you last saw: a mismatch is
            rejected so concurrent user edits are never overwritten.""",
            """
            {
              "type": "object",
              "properties": {
                "plan_id": {"type": "string"},
                "version": {"type": "integer", "description": "Version of the plan you last read; omit to force the write."},
                "operations": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "op": {"type": "string", "enum": ["add_task", "update_task", "remove_task", "update_meta"]},
                      "task_id": {"type": "string", "description": "Target task, required for update_task / remove_task."},
                      "task": {"type": "object", "description": "Task payload for add_task (same shape as create_plan tasks)."},
                      "title": {"type": "string", "description": "New plan title, for update_meta."},
                      "summary_markdown": {"type": "string", "description": "New plan document, for update_meta."}
                    },
                    "required": ["op"]
                  }
                }
              },
              "required": ["plan_id", "operations"]
            }""");

    public static final PlanToolSpec UPDATE_TASK = new PlanToolSpec(
            PlanToolNames.UPDATE_TASK,
            """
            Patch a single task of an existing plan (title, description, status, dependencies, priority,
            acceptance). Use it to record progress on the task you are working on (status=doing) and to
            attach what you learned. Send only the fields that change.""",
            """
            {
              "type": "object",
              "properties": {
                "plan_id": {"type": "string"},
                "task_id": {"type": "string"},
                "version": {"type": "integer", "description": "Version of the plan you last read; omit to force the write."},
                "patch": {
                  "type": "object",
                  "properties": {
                    "title": {"type": "string"},
                    "description": {"type": "string"},
                    "status": {"type": "string", "enum": ["todo", "doing", "blocked", "done"]},
                    "dependencies": {"type": "array", "items": {"type": "string"}},
                    "priority": {"type": "integer"},
                    "acceptance": {"type": "string"}
                  }
                }
              },
              "required": ["plan_id", "task_id", "patch"]
            }""");

    public static final PlanToolSpec COMPLETE_TASK = new PlanToolSpec(
            PlanToolNames.COMPLETE_TASK,
            """
            Mark one task of the plan as done. Call it immediately after the task really is finished,
            judged against its acceptance criterion. When the last open task is completed the plan itself
            is closed and you may write the final answer.""",
            """
            {
              "type": "object",
              "properties": {
                "plan_id": {"type": "string"},
                "task_id": {"type": "string"},
                "version": {"type": "integer", "description": "Version of the plan you last read; omit to force the write."}
              },
              "required": ["plan_id", "task_id"]
            }""");

    /** Contract of the user-side approval command; never registered as a model tool. */
    public static final PlanToolSpec APPROVE_PLAN = new PlanToolSpec(
            PlanToolNames.APPROVE_PLAN,
            "Approve a plan waiting for the human decision and let the agent start implementing it.",
            """
            {
              "type": "object",
              "properties": {"plan_id": {"type": "string"}},
              "required": ["plan_id"]
            }""");

    private PlanToolSpecs() {
    }
}
