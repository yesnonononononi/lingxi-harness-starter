package com.summit.runtime.model;

import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class ModelRetryPolicyTest {
    @Test
    void transparentWrappersDoNotChangeAnExplicitClassification() {
        RetriableModelException transientFailure = new RetriableModelException("temporary", null);
        assertTrue(ModelRetryPolicy.shouldRetry(transientFailure));
        assertTrue(ModelRetryPolicy.shouldRetry(new CompletionException(new ExecutionException(transientFailure))));
        NonRetriableModelException permanentFailure = new NonRetriableModelException("permanent", null);
        assertFalse(ModelRetryPolicy.shouldRetry(permanentFailure));
        assertFalse(ModelRetryPolicy.shouldRetry(new CompletionException(new ExecutionException(permanentFailure))));
    }

    @Test
    void unknownBusinessAndControlFailuresCannotReplayAModelCall() {
        for (Throwable failure : List.of(new IllegalArgumentException("invalid"), new IOException("unclassified"),
                new CancellationException("cancelled"), new InterruptedException("interrupted"),
                new ExecutionInterruptedException(ExecutionInterruptedException.Kind.SUSPEND, "suspended"),
                new RuntimeException(new RetriableModelException("nontransparent wrapper", null)))) {
            assertFalse(ModelRetryPolicy.shouldRetry(failure));
            assertFalse(ModelRetryPolicy.shouldRetry(new CompletionException(new ExecutionException(failure))));
        }
    }
}
