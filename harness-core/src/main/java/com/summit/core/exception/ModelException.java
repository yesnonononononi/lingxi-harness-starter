package com.summit.core.exception;

public class ModelException extends RuntimeException {
    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
