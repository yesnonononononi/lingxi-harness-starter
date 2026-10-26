package com.summit.core.anno;

import com.summit.core.anno.type.ObjectValue;
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
    /** Fixed object-root schema; use parametersJsonSchema for nested object or array items. */
    ObjectValue parameters() default @ObjectValue;
    ToolProperty[] props() default {};
    OtherProperty[] others() default {};
    String parametersJsonSchema() default "";
    String type() default "object";
    int maxOutput() default 5000;
    long timeout() default 60_000L;
    ConcurrentPolicy concurrentPolicy() default ConcurrentPolicy.READ_ONLY;
}
