package com.summit.core.anno.type;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Optional scalar arrays distinguish omission from a valid empty-string value. */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface StringValue {
    int maxLength() default -1;
    int minLength() default -1;
    String pattern() default "";
    String format() default "";
    String[] enums() default {};
    String[] defaultValue() default {};
    String[] constValue() default {};
}
