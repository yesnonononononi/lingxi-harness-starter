package com.summit.harnessexample.common;

/**
 * Business status codes of the unified {@link Result} envelope.
 *
 * <p>The numeric value doubles as the HTTP status when a result is returned through
 * {@link GlobalExceptionHandler}, so the front-end can rely on a single
 * {@code {code, message, data}} contract for both success and failure.</p>
 */
public enum ResultCode {

    SUCCESS(200, "ok"),
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "not found"),
    CONFLICT(409, "conflict"),
    INTERNAL_ERROR(500, "internal error");

    private final int code;
    private final String defaultMessage;

    ResultCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
