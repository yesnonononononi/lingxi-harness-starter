package com.summit.core.runtime.loop;

import java.util.Objects;

public enum InterceptorType {
    BEFORE_INVOKE,
    AFTER_INVOKE,
    LOOP_START,
    LOOP_END,
    AFTER_TOOL_CALL,
    BEFORE_TOOL_CALL,
    BEFORE_COMPLETE,
    RUN_END;

    public  boolean equals(InterceptorType that){
        return Objects.equals(that,this);
    }


}
