package com.summit.core.anno.type;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Scalar items keep this branch from referring back to Value. */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ArrayValue {
    ScalarType itemType() default ScalarType.STRING;
    int minItems() default -1;
    int maxItems() default -1;
    OptionalBoolean uniqueItems() default OptionalBoolean.UNSET;
}
