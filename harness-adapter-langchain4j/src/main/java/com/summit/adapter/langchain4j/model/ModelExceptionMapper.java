package com.summit.adapter.langchain4j.model;

import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.exception.ModelException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.LangChain4jException;

import java.io.IOException;
import java.io.EOFException;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
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
        if (transport != null && (transport.getClass() == IOException.class || transport instanceof EOFException
                || transport instanceof SocketException || transport instanceof SocketTimeoutException
                || transport instanceof UnknownHostException || transport instanceof HttpTimeoutException
                || transport instanceof ClosedChannelException)) {
            return new RetriableModelException(transport.getMessage(), error);
        }
        return error;
    }
}
