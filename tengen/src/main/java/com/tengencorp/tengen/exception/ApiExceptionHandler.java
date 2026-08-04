package com.tengencorp.tengen.exception;

import com.tengencorp.tengen.helper.LogSafe;
import com.tengencorp.tengen.helper.WarningLogRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Maps exceptions to the uniform {@link ErrorResponse} JSON contract.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final WarningLogRateLimiter warningLogRateLimiter = new WarningLogRateLimiter();

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> idempotencyConflict(IdempotencyConflictException e,
                                                             HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, e.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(ConflictException e, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, e.getMessage(), request);
    }

    @ExceptionHandler(RabbitMqConnectorException.class)
    public ResponseEntity<ErrorResponse> rabbitMqConnector(RabbitMqConnectorException e,
                                                            HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> optimisticLock(ObjectOptimisticLockingFailureException e,
                                                        HttpServletRequest request) {
        return error(HttpStatus.CONFLICT,
            "The resource changed in another session; reload and try again", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");
        return error(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e,
                                                    HttpServletRequest request) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof RequestBodyLimitExceededException) {
                warn("request_body_too_large", LogSafe.requestPath(request),
                    "event=security_event name=request_body_too_large method={} path={} limit=enforced",
                    request.getMethod(), LogSafe.requestPath(request));
                return error(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Request body exceeds the configured limit", request);
            }
            cause = cause.getCause();
        }
        return error(HttpStatus.BAD_REQUEST, "Request body is not valid JSON", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> forbidden(AccessDeniedException e, HttpServletRequest request) {
        warn("forbidden_access", LogSafe.requestPath(request),
            "event=security_event name=forbidden_access method={} path={} principal={}",
            request.getMethod(), LogSafe.requestPath(request), LogSafe.principal(request));
        return error(HttpStatus.FORBIDDEN, e.getMessage(), request);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> tooManyRequests(TooManyRequestsException e,
                                                         HttpServletRequest request) {
        return error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> generic(Exception e, HttpServletRequest request) {
        log.error("event=api_failure name=unhandled method={} path={} principal={} exceptionType={}",
            request.getMethod(), LogSafe.requestPath(request), LogSafe.principal(request),
            LogSafe.exceptionType(e), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    private void warn(String category, String stableKey, String message, Object... arguments) {
        if (warningLogRateLimiter.tryAcquire(category, stableKey)) {
            log.warn(message, arguments);
        }
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
            .body(ErrorResponse.of(
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()));
    }
}
