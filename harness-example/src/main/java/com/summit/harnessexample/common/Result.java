package com.summit.harnessexample.common;

import java.util.Objects;

/**
 * Unified HTTP response envelope of the example application.
 *
 * <p>Every controller returns {@code Result} (directly or wrapped in a
 * {@code ResponseEntity} by the exception handler) so all endpoints share the exact
 * same JSON shape:</p>
 *
 * <pre>
 * {
 *   "code":    200,
 *   "message": "ok",
 *   "data":    { ... }   // payload, may be null
 * }
 * </pre>
 *
 * <p>The front-end request interceptor unwraps {@code data} on {@code code == 200}
 * and reads {@code message} for errors, so the contract must not drift.</p>
 *
 * @param <T> payload type
 */
public record Result<T>(int code, String message, T data) {

    // ------------------------------------------------------------------ success

    /** 200 without payload. */
    public static Result<Void> ok() {
        return new Result<>(ResultCode.SUCCESS.code(), ResultCode.SUCCESS.defaultMessage(), null);
    }

    /** 200 with payload. */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.code(), ResultCode.SUCCESS.defaultMessage(), data);
    }

    /** 200 with a custom message and payload. */
    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(ResultCode.SUCCESS.code(), message, data);
    }

    // ------------------------------------------------------------------ failure

    /** Failure with an explicit business code and no payload. */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** Failure with an explicit business code and a payload (e.g. conflict details). */
    public static <T> Result<T> fail(int code, String message, T data) {
        return new Result<>(code, message, data);
    }

    /** Failure derived from a {@link ResultCode}, falling back to its default message. */
    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return new Result<>(resultCode.code(),
                message == null || message.isBlank() ? resultCode.defaultMessage() : message,
                null);
    }

    /** Whether this result represents a successful call. */
    public boolean success() {
        return code == ResultCode.SUCCESS.code();
    }

    /** Convenience factory used by the tests / callers that want a typed empty payload. */
    public static <T> Result<T> of(int code, String message, T data) {
        return new Result<>(code, Objects.requireNonNullElse(message, ""), data);
    }
}
