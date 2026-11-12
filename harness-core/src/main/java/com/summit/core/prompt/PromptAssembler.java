package com.summit.core.prompt;

import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.runtime.workspace.Workspace;

/**
 * Renders the leading system message of one execution.
 *
 * <p>It renders the <em>stable</em> half of the request only: where the agent runs and the prompt
 * the application wrote. Everything that changes between rounds — remote-tool résumés, skills,
 * memory, the task manifest — is assembled by the per-round context renderer and sent as a user
 * message, so this text stays byte-identical and keeps the provider's prompt cache warm.</p>
 *
 * <p>One assembler instance renders one execution; {@link #startWithWorkspace(Workspace)} returns
 * the instance to continue with, so implementations may either mutate and return {@code this} or
 * hand back a fresh object.</p>
 */
public interface PromptAssembler {

    /** Opens the assembly over the given workspace; the returned instance receives the rest. */
    PromptAssembler startWithWorkspace(Workspace workspace);

    /** Appends the application's own prompt verbatim. A blank prompt adds no section at all. */
    PromptAssembler withBusinessPrompt(String businessPrompt);

    /** The finished system message. */
    SystemMessageEntity complete();
}
