package com.summit.core.exception;

public class TokenBudgetExceededException extends RuntimeException {
    public TokenBudgetExceededException(String message) {
        super(message);
    }
}
