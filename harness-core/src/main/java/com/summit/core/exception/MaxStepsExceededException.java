package com.summit.core.exception;

public class MaxStepsExceededException extends RuntimeException {
    public MaxStepsExceededException(String message) {
        super(message);
    }
}
