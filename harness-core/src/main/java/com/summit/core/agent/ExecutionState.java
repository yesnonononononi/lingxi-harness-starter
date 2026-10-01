package com.summit.core.agent;

public enum ExecutionState {
    CREATED,

    RUNNING,

    SUSPENDED,

    COMPLETED,

    FAILED,

    CANCELLED;

    public  boolean isTerminal() {
        return this == ExecutionState.COMPLETED || this == ExecutionState.CANCELLED || this == ExecutionState.FAILED;
    }


    public static ExecutionState of(String name){
        if(name == null || name.isEmpty())return null;
        return valueOf(name.toUpperCase());
    }

}
