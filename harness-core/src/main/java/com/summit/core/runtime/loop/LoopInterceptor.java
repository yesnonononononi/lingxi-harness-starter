package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.tool.ToolExecuteResult;
import java.util.List;

/** Synchronous callbacks on the execution thread. Pre/processing callback failures fail the run. */
public interface LoopInterceptor {
    LoopInterceptor NOOP = new LoopInterceptor() {};
    /** Append external input here, before context budget checks and model request construction. */
    default void onBeforeModelInvoke(LoopContext context) {}
    default void onAfterModelInvoke(LoopContext context, ChatResponseEntity response) {}
    /** Called after a successful tool batch is committed or its compaction is reconciled. */
    default void onAfterToolCall(LoopContext context, List<ToolExecuteResult> results) {}
    default void onBeforeToolCall(LoopContext context) {}
    /** Always paired with an entered round, including failure/suspension; exceptions are logged. */
    default void onLoopEnd(LoopContext context) {}
    default void onLoopStart(LoopContext context) {}
    /** Once on COMPLETED/CANCELLED/FAILED, never on SUSPENDED; exceptions are logged. */
    default void onRunEnd(Execution execution) {}
}
