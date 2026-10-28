package com.summit.core.anno.type;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Empty scalar arrays preserve every valid long value without sentinel collisions. */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface IntegerValue {
    long[] minimum() default {};
    long[] maximum() default {};
    long[] exclusiveMinimum() default {};
    long[] exclusiveMaximum() default {};
    long[] multiple() default {};
    long[] defaultValue() default {};
    long[] constValue() default {};
    long[] enums() default {};
}
