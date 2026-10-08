package com.summit.runtime.compact;

/** Prompt constants for the model deep compaction ({@code compact_context}). */
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
}
