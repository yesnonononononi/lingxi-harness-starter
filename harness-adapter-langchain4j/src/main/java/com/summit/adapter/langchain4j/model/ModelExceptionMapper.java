package com.summit.adapter.langchain4j.model;

import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.exception.ModelException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.LangChain4jException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.security.cert.CertificateException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** SDK classifications and transport failures cross into the framework's retry contract. */
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
        if (classified instanceof NonRetriableException) {
            return new NonRetriableModelException(classified.getMessage(), error);
        }
        ModelException providerError = ProviderErrorMapper.map(classified, error);
        if (providerError != null) return providerError;
        if (classified instanceof RetriableException) {
            return new RetriableModelException(classified.getMessage(), error);
        }
        Throwable transport = classified;
        while (transport != null && (transport.getClass() == LangChain4jException.class
                || transport instanceof UncheckedIOException) && transport.getCause() != null) {
            transport = transport.getCause();
        }
        // Transport implementations add IOException subtypes; enumerating them silently loses retry coverage.
        if (transport instanceof IOException && !isNonRetryableTransport(transport)) {
            return new RetriableModelException(transport.getMessage(), error);
        }
        return error;
    }

    private static boolean isNonRetryableTransport(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            // Socket timeouts share InterruptedIOException's hierarchy but do not request cancellation.
            if (cause instanceof InterruptedIOException && !(cause instanceof SocketTimeoutException)
                    || cause instanceof ClosedByInterruptException || cause instanceof InterruptedException
                    || cause instanceof CancellationException || cause instanceof CertificateException) return true;
        }
        return false;
    }
}
