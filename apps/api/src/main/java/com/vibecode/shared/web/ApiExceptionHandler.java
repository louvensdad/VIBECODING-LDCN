package com.vibecode.shared.web;

import com.vibecode.identity.application.EmailAlreadyRegisteredException;
import com.vibecode.identity.domain.CurrentUserProvider;
import com.vibecode.identity.domain.PasswordPolicy;
import com.vibecode.identity.ratelimit.domain.RateLimitExceededException;
import com.vibecode.identity.web.AuthController;
import com.vibecode.shared.domain.DomainRuleException;
import com.vibecode.shared.domain.ResourceNotFoundException;
import com.vibecode.vault.domain.VaultCryptographyException;
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

  /**
   * Authentication problems.
   *
   * <p>Bad password, unknown address and a disabled account all land here with the same body. A
   * caller must not be able to tell which of the three happened, or the endpoint becomes a way to
   * test which addresses have accounts.
   */
  @ExceptionHandler({
    AuthController.InvalidCredentialsException.class,
    CurrentUserProvider.NotAuthenticatedException.class
  })
  ResponseEntity<ApiError> unauthenticated(RuntimeException exception) {
    return build(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Credenciais inválidas.");
  }

  /**
   * Too many attempts.
   *
   * <p>The body says nothing about which limit was reached, how many attempts remain, or whether
   * the account exists — all of which would help an automated attempt pace itself.
   */
  @ExceptionHandler(RateLimitExceededException.class)
  ResponseEntity<ApiError> rateLimited(RateLimitExceededException exception) {
    return build(
        HttpStatus.TOO_MANY_REQUESTS,
        "TOO_MANY_REQUESTS",
        "Muitas tentativas. Tente novamente mais tarde.");
  }

  /** Registration against an address that already has an account. */
  @ExceptionHandler(EmailAlreadyRegisteredException.class)
  ResponseEntity<ApiError> emailTaken(EmailAlreadyRegisteredException exception) {
    return build(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", exception.getMessage());
  }

  /** The message states the rule that was broken and never echoes the password. */
  @ExceptionHandler(PasswordPolicy.WeakPasswordException.class)
  ResponseEntity<ApiError> weakPassword(PasswordPolicy.WeakPasswordException exception) {
    return build(HttpStatus.UNPROCESSABLE_ENTITY, "WEAK_PASSWORD", exception.getMessage());
  }

  /** A well-formed request that would break a domain rule. */
  @ExceptionHandler(DomainRuleException.class)
  ResponseEntity<ApiError> domainRule(DomainRuleException exception) {
    return build(HttpStatus.UNPROCESSABLE_ENTITY, "DOMAIN_RULE_VIOLATION", exception.getMessage());
  }

  /** Guards inside the domain — completing an unfinished task, reviewing a reviewed proposal. */
  @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
  ResponseEntity<ApiError> illegalState(RuntimeException exception) {
    return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE", exception.getMessage());
  }

  /**
   * Anything the vault could not encrypt, decrypt, wrap or unwrap.
   *
   * <p>One status and one message for every cause. Whether the key is wrong, the ciphertext was
   * altered, the AAD does not match or the row is gone, the caller learns the same thing: it did
   * not work. Distinguishing them over HTTP would turn this endpoint into an oracle that tells an
   * attacker which of their guesses is closer.
   *
   * <p>The exception is logged without its message resolved into the response, and the vault has
   * already written the failure to the audit trail.
   */
  @ExceptionHandler(VaultCryptographyException.class)
  ResponseEntity<ApiError> vaultFailure(VaultCryptographyException exception) {
    log.error("Vault cryptographic operation failed", exception);
    return build(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "Não foi possível processar a credencial.");
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
