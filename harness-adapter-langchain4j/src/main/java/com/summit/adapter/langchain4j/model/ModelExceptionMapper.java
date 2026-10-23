package com.summit.adapter.langchain4j.model;

import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Only explicit SDK classifications cross into the framework's retry contract. */
final class ModelExceptionMapper {
    private ModelExceptionMapper() {
    }

    static RuntimeException map(RuntimeException error) {
        return (RuntimeException) map((Throwable) error);
    }

    static Throwable map(Throwable error) {
        Throwable classified = error;
        while ((classified instanceof CompletionException || classified instanceof ExecutionException)
                && classified.getCause() != null) {
            classified = classified.getCause();
        }
        if (classified instanceof RetriableException) {
            return new RetriableModelException(classified.getMessage(), error);
        }
        if (classified instanceof NonRetriableException) {
            return new NonRetriableModelException(classified.getMessage(), error);
        }
        return error;
    }
}
