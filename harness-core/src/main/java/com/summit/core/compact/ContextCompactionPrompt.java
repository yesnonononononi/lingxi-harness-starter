package com.summit.core.compact;

/**
 * Prompt constants for the model deep compaction ({@code compact_context}).
 *
 * <p>Shared by both entry points so the prompt text is maintained once:</p>
 * <ul>
 *   <li>the {@code compact_context} tool executor (model-initiated);</li>
 *   <li>{@code DefaultModelCompacter} (checkpoint-initiated, blocking model compaction).</li>
 * </ul>
 */
public final class ContextCompactionPrompt {

    private ContextCompactionPrompt() {
    }

    /** Base compaction system prompt: asks the compact model for a single valid JSON summary object. */
    public static final String BASE_COMPACTION_PROMPT = """
            You are a context compression assistant. Compress the given conversation history into a concise but complete summary so the conversation can be continued seamlessly.
            Keep all important facts, decisions, user requirements, tool calls and their results, and the current task state.
            Write the summary in the same language as the conversation.
            Reply with EXACTLY ONE valid JSON object and nothing else: no markdown code fences, no explanation, no surrounding text.
            Escape every newline and tab inside string values as \\n / \\t, so each string occupies a single line.
            Schema (field names must be exactly as below, "completed"/"pending" are arrays of strings):
            {
              "goal": "the goal of the conversation",
              "summary": "a detailed but compact summary of the conversation history",
              "completed": ["task 1", "task 2"],
              "pending": ["task 1", "task 2"],
              "state": "DONE"
            }
             Value of "state" must be one of: DONE or FAILED.
            """;

    /** Separator before the verbatim plan text appended to the compaction input. */
    public static final String PROTECTED_PLAN_MARKER = "\n\n[protected plan - keep verbatim]\n";

    /** Plan-protection prompt appended when the session holds a produced plan; its text must stay verbatim. */
    public static final String PLAN_PROTECTION_PROMPT = """
            IMPORTANT — The conversation history contains an implementation plan that the agent has already produced.
            The full plan text and every single step MUST be kept verbatim: it is only allowed to apply a light summary to the rest of the history.
            It is FORBIDDEN to delete, shorten or reword any part of the plan; include the whole plan under "pending".
            """;
}
