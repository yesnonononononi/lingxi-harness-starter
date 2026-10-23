package com.summit.core.exception;

/** Marks a model failure that the provider classifies as transient. */
public class RetriableModelException extends ModelException {
    public RetriableModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
