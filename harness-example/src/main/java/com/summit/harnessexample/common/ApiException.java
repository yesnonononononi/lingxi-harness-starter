package com.summit.harnessexample.common;

import lombok.Getter;

/**
 * Business exception carrying the HTTP/business code and an optional error payload.
 *
 * <p>Services throw {@code ApiException} instead of hand-building error responses; the
 * {@link GlobalExceptionHandler} turns it into the unified {@link Result} envelope while
 * keeping the HTTP status aligned with the business code.</p>
 */
@Getter
public class ApiException extends RuntimeException {

    /** Business code, also used as the HTTP status. */
    private final int code;

    /** Optional error payload (e.g. optimistic-concurrency conflict details). */
    private final transient Object data;

    public ApiException(int code, String message) {
        this(code, message, null);
    }

    public ApiException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public static ApiException of(ResultCode resultCode, String message) {
        return new ApiException(resultCode.code(),
                message == null || message.isBlank() ? resultCode.defaultMessage() : message);
    }

    public static ApiException badRequest(String message) {
        return of(ResultCode.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return of(ResultCode.NOT_FOUND, message);
    }

    public static ApiException notFound(String message, Object data) {
        return new ApiException(ResultCode.NOT_FOUND.code(), message, data);
    }

    public static ApiException conflict(String message) {
        return of(ResultCode.CONFLICT, message);
    }

    public static ApiException conflict(String message, Object data) {
        return new ApiException(ResultCode.CONFLICT.code(), message, data);
    }

    public static ApiException internalError(String message) {
        return of(ResultCode.INTERNAL_ERROR, message);
    }
}
