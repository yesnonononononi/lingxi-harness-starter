package com.summit.core.anno.type;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface BooleanValue {
    OptionalBoolean defaultValue() default OptionalBoolean.UNSET;
    OptionalBoolean constValue() default OptionalBoolean.UNSET;
}
