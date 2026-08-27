package com.summit.harnesscore.interceptor;

public interface InterceptorProcessor<T extends RuntimeInterceptor,R > {
    Object proceed(InvocationContext<R> invocationContext) throws Throwable;
}
