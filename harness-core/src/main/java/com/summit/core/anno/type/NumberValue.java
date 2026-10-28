package com.summit.core.anno.type;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Empty scalar arrays distinguish omitted constraints from explicit numeric values. */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface NumberValue {
    double[] minimum() default {};
    double[] maximum() default {};
    double[] exclusiveMinimum() default {};
    double[] exclusiveMaximum() default {};
    double[] multiple() default {};
    double[] defaultValue() default {};
    double[] constValue() default {};
    double[] enums() default {};
}
