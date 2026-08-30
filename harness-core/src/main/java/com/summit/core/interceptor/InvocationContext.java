package com.summit.core.interceptor;

import lombok.AllArgsConstructor;import lombok.Builder;import lombok.Data;

import java.lang.reflect.Method;
@Builder
@AllArgsConstructor
@Data
public class InvocationContext<T> {
    private Method method;
    private T context;
    private Object target;
}
