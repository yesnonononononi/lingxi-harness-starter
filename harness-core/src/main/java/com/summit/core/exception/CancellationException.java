package com.summit.core.exception;

public class CancellationException extends NonRetriableModelException {
    /** Keeps the framework cancellation type distinct from JDK future cancellation. */
    public static CancellationException create() {
        return new CancellationException();
    }
    public CancellationException(String message) {
        super(message,null);
    }
    public CancellationException(String message,Throwable throwable) {
        super(message,throwable);
    }
    public CancellationException() {
        super("process has been canceled just now",null);
    }
}
