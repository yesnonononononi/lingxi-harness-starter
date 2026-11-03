package com.summit.core.runtime.loop;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a loop callback on a method visible from the type exposed by Spring,
 * as returned by {@code ApplicationContext.getType(beanName)}.
 * If an {@code @Bean} method exposes an interface type, annotate the interface method;
 * the implementation's {@code @Override} method will be invoked correctly.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgenticLoopListener {
    InterceptorType value();
    String name();
    int order();
    boolean catchErr() default false;
}
