package com.summit.harnesscore.interceptor;

public interface RuntimeInterceptor<T> {
    /**
     * Freely control the logic before and after method calls
     *
     * @param invocationContext invocation context
     */
    void pre(InvocationContext<T> invocationContext) ;

    void after(InvocationContext<T> invocationContext,Object result);

    /**
     * interceptor order, the smaller the number, the higher the priority. the default is 0 .the lowest is -1000
     *
     * @return interceptor order
     */
    default Integer order() {
        return 0;
    }

    default void onError(Throwable throwable,InvocationContext<T> invocationContext) throws Throwable  {
        throw throwable;
    }
}
