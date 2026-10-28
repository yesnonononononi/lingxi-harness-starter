package com.summit.runtime.model;

import com.summit.core.exception.RetriableModelException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Only classified model failures may replay an invocation. */
final class ModelRetryPolicy {
    private ModelRetryPolicy() { }

    static boolean shouldRetry(Throwable error) {
        return unwrap(error) instanceof RetriableModelException;
    }

    static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
