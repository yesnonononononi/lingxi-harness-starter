package com.summit.core.anno.type;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Value {
    ValueType type();
    ArrayValue arrayValue() default @ArrayValue;
    IntegerValue integerValue() default @IntegerValue;
    StringValue stringValue() default @StringValue;
    BooleanValue booleanValue() default @BooleanValue;
    NumberValue numberValue() default @NumberValue;
    String description() default "";
}
