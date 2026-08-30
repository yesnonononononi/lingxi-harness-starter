package com.summit.runtime;

import com.summit.core.interceptor.InterceptorProcessor;
import com.summit.core.interceptor.InvocationContext;
import com.summit.core.interceptor.RuntimeInterceptor;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;


@Slf4j
public class DefaultInterceptorProcessor<I extends RuntimeInterceptor<T>,T> implements InterceptorProcessor<I, T> {
    private final List<I> interceptorList;


    public DefaultInterceptorProcessor(List<I> interceptorList) {
        this.interceptorList = interceptorList.stream()
                .sorted(Comparator.comparingInt(I::order))
                .toList();
    }

    @Override
    public Object proceed(InvocationContext<T> invocationContext) throws Throwable {
        Method method = invocationContext.getMethod();
        T context = invocationContext.getContext();
        Object result;
        doPre(invocationContext);
        try {
            result = method.invoke(invocationContext.getTarget(), context);
        } catch (InvocationTargetException e) {

            doOnError(invocationContext, e.getTargetException());
            throw e.getTargetException();
        }

        doAfter(invocationContext, result);
        return result;

    }


    private void doPre(InvocationContext<T> invocationContext) {   // forEach can't throw checked exception
        for (I interceptor : this.interceptorList) {
            interceptor.pre(invocationContext);
        }
    }

    private void doAfter(InvocationContext<T> invocationContext, Object result) {
        for (int i = this.interceptorList.size()-1; i >= 0; i--) {
            I interceptor = this.interceptorList.get(i);
            interceptor.after(invocationContext, result);
        }
    }

    private void doOnError(InvocationContext<T> invocationContext, Throwable throwable)  {
        for (I interceptor : this.interceptorList) {
            try {
                interceptor .onError(throwable, invocationContext);
            } catch (Throwable e) {
                log.error("Error occurred while processing error", e);
            }
        }
    }
}
