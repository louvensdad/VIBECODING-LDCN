package com.vibecode.shared.web;

import com.vibecode.shared.domain.ResourceNotFoundException;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Produces one consistent error body for the whole API. */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler({ResourceNotFoundException.class, NoSuchElementException.class})
  ResponseEntity<ApiError> notFound(RuntimeException exception) {
    return build(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
    List<ApiError.FieldViolation> violations =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage()))
            .toList();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                "The request body is invalid.",
                violations));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException exception) {
    return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request body could not be read.");
  }

  /**
   * Last-resort handler.
   *
   * <p>Spring's own web exceptions (unknown route, wrong method, unsupported media type) already
   * carry the right status and are reported with it — collapsing them into 500 would hide an
   * ordinary client mistake behind a server error. Anything else is genuinely unexpected: the cause
   * goes to the log and the client gets a generic message, so internal detail never leaks through
   * the API.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> unexpected(Exception exception) {
    if (exception instanceof ErrorResponse errorResponse) {
      HttpStatusCode status = errorResponse.getStatusCode();
      return ResponseEntity.status(status)
          .body(
              ApiError.of(
                  status.value(),
                  status.is4xxClientError() ? "BAD_REQUEST" : "INTERNAL_ERROR",
                  errorResponse.getBody().getTitle()));
    }
    log.error("Unhandled exception while serving a request", exception);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected internal error.");
  }

  private ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(ApiError.of(status.value(), code, message));
  }
}
