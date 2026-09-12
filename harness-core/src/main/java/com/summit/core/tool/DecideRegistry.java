package com.summit.core.tool;

import lombok.NonNull;

public interface DecideRegistry<A extends AbstractApprovalGate<D>,D> {

    A register(@NonNull String toolExecutionId, @NonNull A decide);

    A get(@NonNull String toolExecutionId);


    boolean decide( @NonNull String toolExecutionId, @NonNull D decision);


    void unregister(@NonNull String toolExecutionId);

    int size();
}
