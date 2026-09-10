package com.summit.harnessexample.common;

import com.summit.core.internalUtils.PlanArgumentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Central translation of every exception into the unified {@link Result} envelope.
 *
 * <p>Controllers therefore stay free of try/catch + response-building boilerplate:
 * they either return {@code Result.ok(...)} or let a service throw
 * {@link ApiException} and this advice shapes the error (status + {@code code} +
 * {@code message} + optional {@code data}).</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Expected business failures raised deliberately by the service layer. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result<Object>> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getCode())
                .body(Result.fail(e.getCode(), e.getMessage(), e.getData()));
    }

    /** Invalid arguments (blank path, malformed container path, ...) are client errors. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Result.fail(ResultCode.BAD_REQUEST, e.getMessage()));
    }

    /**
     * Safety net for a plan-argument violation that escaped a service (normally converted
     * to a 409 with the latest plan). Kept here so no caller can leak a 500 for it.
     */
    @ExceptionHandler(PlanArgumentException.class)
    public ResponseEntity<Result<Object>> handlePlanArgument(PlanArgumentException e) {
        return ResponseEntity.status(ResultCode.CONFLICT.code())
                .body(Result.fail(ResultCode.CONFLICT, e.getMessage()));
    }

    /**
     * Fallback: preserves the status of Spring MVC's own errors ({@link ErrorResponse}),
     * everything else is reported as an internal error. This keeps 404s for unknown
     * routes and 405s for wrong methods intact.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleUnexpected(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            int status = errorResponse.getStatusCode().value();
            String detail = errorResponse.getBody() == null ? null : errorResponse.getBody().getDetail();
            return ResponseEntity.status(status).body(Result.fail(status, detail));
        }
        log.error("unhandled exception", e);
        return ResponseEntity.internalServerError().body(Result.fail(ResultCode.INTERNAL_ERROR, e.getMessage()));
    }
}
