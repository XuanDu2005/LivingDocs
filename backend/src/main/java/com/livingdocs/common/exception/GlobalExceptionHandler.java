package com.livingdocs.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.dao.DataIntegrityViolationException;

import com.livingdocs.common.exception.OAuthExchangeException;

import java.util.List;

/**
 * Centralised exception → HTTP response mapping.
 *
 * <p>Every controller in the application benefits from consistent error
 * shapes without each one having to catch exceptions itself.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidCodeException.class)
    public ResponseEntity<ApiError> handleInvalidCode(InvalidCodeException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ApiError> handleEmailNotVerified(EmailNotVerifiedException ex, HttpServletRequest req) {
        ApiError body = ApiError.builder()
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("EMAIL_NOT_VERIFIED")
                .message("Email is not verified")
                .path(req.getRequestURI())
                .metadata(java.util.Map.of("email", ex.getEmail()))
                .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(OAuthExchangeException.class)
    public ResponseEntity<ApiError> handleOAuthExchange(OAuthExchangeException ex, HttpServletRequest req) {
        log.warn("OAuth exchange failure on {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY, ex.getMessage(), req);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), req);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiError> handleTooManyRequests(TooManyRequestsException ex, HttpServletRequest req) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password", req);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "Access denied", req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest req) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .toList();
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                "One or more fields are invalid",
                req.getRequestURI(),
                violations
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * A required query parameter was not supplied by the caller. This is a
     * client mistake, not a server fault — map it to 400 so the caller
     * sees which parameter is missing instead of a generic 500.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex,
                                                        HttpServletRequest req) {
        log.warn("Missing request parameter '{}' on {}", ex.getParameterName(), req.getRequestURI());
        return build(HttpStatus.BAD_REQUEST,
                "Required parameter '" + ex.getParameterName() + "' is missing", req);
    }

    /**
     * The request body could not be read.
     *
     * <p>Jackson raises this when the body is not JSON at all, or is
     * JSON of the wrong shape — a string where an object was expected,
     * a number where a UUID was expected, a truncated document. The
     * caller made a mistake, so the answer is 400 and a message that
     * says the body is malformed.
     *
     * <p>Without this handler the exception fell through to the
     * catch-all below and answered 500, which told the caller their
     * server was broken when in fact their request was. It also wrote
     * a full stack trace into the log for what is a routine client
     * error, which buried the failures that actually needed attention.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                          HttpServletRequest req) {
        log.warn("Malformed request body on {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Request body is malformed or unreadable", req);
    }

    /**
     * An unmapped URL.
     *
     * <p>Spring raises this when no controller matches the path and the
     * request falls through to the static-resource handler. It is a
     * client mistake — the endpoint does not exist — so it maps to 404
     * instead of the generic 500 the catch-all below would produce.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex,
                                                    HttpServletRequest req) {
        log.debug("No handler for {} on {}", ex.getResourcePath(), req.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "Not found", req);
    }

    /**
     * A database constraint was violated.
     *
     * <p>This used to fall through to the catch-all and answer 500,
     * which is the wrong answer for the common case: the caller tried
     * to record something that already exists (a second verdict in the
     * same tier, a duplicate delegation) and the request collided with
     * a uniqueness rule. That is a conflict with the current state of
     * the resource, not a server fault — 409 says so, and the client
     * can retry with a different action.
     *
     * <p>The message names the constraint when the driver exposes it,
     * because "duplicate key" is far more useful to the caller than a
     * generic message. It is deliberately kept short and free of SQL
     * internals beyond the constraint name.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex,
                                                         HttpServletRequest req) {
        String message = ex.getMostSpecificCause().getMessage();
        String constraint = null;
        if (message != null) {
            // Postgres reports the constraint name as
            // `duplicate key value violates unique constraint "name"`.
            int marker = message.indexOf("\"");
            if (marker >= 0) {
                int end = message.indexOf('"', marker + 1);
                if (end > marker) {
                    constraint = message.substring(marker + 1, end);
                }
            }
        }
        log.warn("Data integrity violation on {}: {}", req.getRequestURI(),
                constraint != null ? constraint : ex.getClass().getSimpleName());
        ApiError body = ApiError.builder()
                .status(HttpStatus.CONFLICT.value())
                .error("CONFLICT")
                .message(constraint != null
                        ? "The request conflicts with the current state (constraint: "
                                + constraint + ")"
                        : "The request conflicts with the current state")
                .path(req.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * A path variable or request parameter could not be converted to
     * the declared type — a malformed UUID, a number that is not a
     * number.
     *
     * <p>This is a client mistake, so it maps to 400. Without this
     * handler it fell through to the catch-all and answered 500, which
     * told the caller the server broke when in fact it sent nonsense.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest req) {
        log.warn("Malformed '{}' on {}: {}", ex.getName(), req.getRequestURI(),
                ex.getValue());
        return build(HttpStatus.BAD_REQUEST,
                "'" + ex.getName() + "' has an invalid value", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAny(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}: {}", req.getRequestURI(), ex.toString(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", req);
    }

    private ApiError.FieldViolation toViolation(FieldError fe) {
        return new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), message, req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}