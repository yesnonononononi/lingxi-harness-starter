package com.summit.core.anno;

import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolExecutor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {
    String name();
    String id();
    String description();
    Class<? extends ToolExecutor> executor();
    ToolProperty[] props() default {};
    OtherProperty[] others() default {};
    String type() default "object";
    int maxOutput() default 5000;
    long timeout() default 60_000L;
    ConcurrentPolicy concurrentPolicy() default ConcurrentPolicy.READ_ONLY;
}
