package com.summit.core.exception;

/** Marks a model failure for which repeating the same request will not help. */
public class NonRetriableModelException extends ModelException {
    public NonRetriableModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
