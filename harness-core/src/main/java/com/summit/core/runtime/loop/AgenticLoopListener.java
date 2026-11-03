package com.summit.core.runtime.loop;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgenticLoopListener {
    InterceptorType value();
    String name();
    int order();
    boolean catchErr() default false;
}
