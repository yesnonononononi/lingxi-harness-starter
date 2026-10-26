package com.summit.core.anno.type;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Only the root may be an object, so annotation element types remain acyclic. */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ObjectValue {
    ToolProperty[] properties() default {};
    String[] required() default {};
    OptionalBoolean additionalProperties() default OptionalBoolean.UNSET;
}
