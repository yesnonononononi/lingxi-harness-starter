package com.summit.core.interceptor;

public interface InterceptorProcessor<R> {
    Object proceed(InvocationContext<R> invocationContext) throws Throwable;
}
