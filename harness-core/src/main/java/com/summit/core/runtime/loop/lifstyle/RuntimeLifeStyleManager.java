package com.summit.core.runtime.loop.lifstyle;

import com.summit.core.agent.Execution;


public interface RuntimeLifeStyleManager {

    void onStart(Execution execution);

    void onCancel(Execution execution);

    void onSuspend(Execution execution);

    void onComplete(Execution execution);

    void onError(Execution execution, Exception e);

    void onResume(Execution snapshot);
}
