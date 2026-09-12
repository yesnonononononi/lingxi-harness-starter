package com.summit.core.runtime;

import com.summit.core.agent.Execution;


public interface RuntimeLifeStyleManager  {

    void onStart(Execution execution);

    void onCancel(Execution execution);


    void onComplete(Execution execution);

    void onError(Execution execution, Exception e);
}
