package com.summit.core.interceptor;

public interface RuntimeInterceptor<T> {
    /**
     * Decide whether the target invocation may proceed.
     *
     * <p>Called on every interceptor (in ascending {@link #order()}) before {@link #pre}.
     * Returning a non-null value short-circuits the whole chain: the underlying target is
     * <b>not</b> invoked and the returned object becomes the invocation result as-is.
     * A typical use is returning a {@code ToolExecuteResult} carrying
     * {@code ToolResultType.CONFIRM_REQUIRED} to suspend a tool call for human approval
     * without touching the executor. Returning {@code null} means "allow", and the next
     * interceptor is consulted.</p>
     *
     * @param invocationContext invocation context
     * @return non-null to short-circuit the chain with that result, {@code null} to continue
     */
    default Object preDecide(InvocationContext<T> invocationContext) {
        return null;
    }

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
