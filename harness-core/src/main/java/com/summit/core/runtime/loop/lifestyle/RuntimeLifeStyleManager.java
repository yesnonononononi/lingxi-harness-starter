package com.summit.core.runtime.loop.lifestyle;

import com.summit.core.agent.Execution;


/** Notifications for framework-owned transitions. Implementations must not mutate execution state. */
public interface RuntimeLifeStyleManager {

    void onStart(Execution execution);

    void onCancel(Execution execution);

    void onSuspend(Execution execution);

    void onComplete(Execution execution);

    void onError(Execution execution, Exception e);

    void onResume(Execution snapshot);
}
