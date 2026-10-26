package com.summit.core.anno;

public @interface ToolArgument {
    String type() default "string";
    String description() default "";
    boolean isRequired() default true;
}
