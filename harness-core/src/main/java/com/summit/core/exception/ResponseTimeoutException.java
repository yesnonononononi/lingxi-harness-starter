package com.summit.core.exception;

public class ResponseTimeoutException extends RetriableModelException{
    public ResponseTimeoutException() {
        super(null,null);
    }

    public ResponseTimeoutException(String message, Throwable cause) {
        super(message,cause);
    }
}
