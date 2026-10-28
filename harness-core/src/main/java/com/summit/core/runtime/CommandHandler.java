package com.summit.core.runtime;

import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;
import java.io.IOException;

/** Observers share one completion; waiting does not own the command lifecycle. */
public interface CommandHandler {
    String outputSnapshot();
    CommandResult await() throws IOException, InterruptedException;
    StopResult stop() throws IOException, InterruptedException;
    boolean isDone();

    /** stopped is true only after an active command has been stopped and its result collected. */
    record StopResult(boolean stopped, CommandResult result) { }
}