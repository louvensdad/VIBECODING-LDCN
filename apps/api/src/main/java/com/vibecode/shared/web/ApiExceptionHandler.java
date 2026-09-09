package com.vibecode.shared.web;

import com.vibecode.identity.application.EmailAlreadyRegisteredException;
import com.vibecode.identity.domain.CurrentUserProvider;
import com.vibecode.identity.domain.PasswordPolicy;
import com.vibecode.identity.ratelimit.domain.RateLimitExceededException;
import com.vibecode.identity.web.AuthController;
import com.vibecode.shared.domain.DomainRuleException;
import com.vibecode.shared.logging.ExpectedHttpErrorLog;
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
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Produces one consistent error body for the whole API. */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /**
   * Where an error the caller caused is recorded instead of being traced.
   *
   * <p>Injected rather than instantiated so the tally is one per application, and so a test can
   * read it. Only the branches below that have already concluded "this is the caller's mistake"
   * touch it; the server-fault branch of {@link #unexpected(Exception)} does not, and must not.
   */
  private final ExpectedHttpErrorLog expectedErrors;

  ApiExceptionHandler(ExpectedHttpErrorLog expectedErrors) {
    this.expectedErrors = expectedErrors;
  }

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
   * A path variable or request parameter that could not be converted to its declared type.
   *
   * <p>{@code /api/projects/not-a-uuid}, {@code /api/provider-accounts/not-a-uuid} and
   * {@code /api/projects/{id}/context/compile} reached by {@code GET} all land here. Without this
   * mapping they fell through to {@link #unexpected(Exception)} — because {@code
   * MethodArgumentTypeMismatchException} is not one of Spring's own {@code ErrorResponse} web
   * exceptions — and were reported as 500 with a stack trace in the log. Every one of them is an
   * ordinary client mistake, and reporting a client mistake as a server fault both misleads the
   * caller and fills the log with noise that hides real failures.
   *
   * <p>The message names the parameter and nothing else. The rejected value is deliberately absent:
   * it is caller-supplied text, it appears in the exception's own message together with the target
   * type and the converter's complaint, and echoing any of that would put an internal detail on the
   * wire for no gain — the caller already knows what they sent.
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                "The request could not be read.",
                List.of(
                    new ApiError.FieldViolation(
                        exception.getName(), "is not a valid value for this parameter"))));
  }

  /**
   * An {@code Accept} header naming nothing this API can produce.
   *
   * <p>The status was always right; getting to it was not. Without this branch the exception
   * reached {@link #unexpected(Exception)}, which built an {@code ApiError} body — and Spring then
   * could not serialise that body either, for the same reason it could not serialise the original
   * response, so the error handler itself failed. Spring reports a failing {@code @ExceptionHandler}
   * at WARN with the throwable attached: 189 frames in the log, on every request, triggered by a
   * header the caller chooses. Measured on this codebase before this branch existed, not estimated.
   *
   * <p>The fix is to answer with no body at all, which is the only honest answer available: the
   * caller has said which representations they accept and this API produces none of them, so there
   * is nothing left to write. Nothing is serialised, nothing fails, and what the caller sees does
   * not move — the 406 already arrived with an empty body and no content type, because the
   * {@code ApiError} that {@link #unexpected(Exception)} built was never successfully written. That
   * was measured against the running application before this branch was added; this is a logging
   * fix and not a change to the error contract.
   *
   * <p>Declared explicitly rather than folded into the {@code ErrorResponse} branch below, because
   * that branch answers with a JSON body and a JSON body is exactly what cannot be produced here.
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<Void> notAcceptable(HttpMediaTypeNotAcceptableException exception) {
    expectedErrors.record(HttpStatus.NOT_ACCEPTABLE.value(), exception.getClass().getSimpleName());
    return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
  }

  /**
   * Last-resort handler, and the one place in this class where the two kinds of failure are told
   * apart. The distinction is the point, so it is written out rather than left to a filter that
   * happens to match:
   *
   * <ul>
   *   <li><b>Expected.</b> Spring's own web exceptions carrying a 4xx — unknown route, wrong
   *       method, unsupported media type — are client mistakes. They keep their status and their
   *       body, and they are now <em>counted</em> by {@link ExpectedHttpErrorLog} rather than
   *       passing in silence. That is more signal than before, not less: this branch previously
   *       returned without logging anything at all.
   *   <li><b>Unexpected.</b> Everything else is a fault in this application. It keeps exactly the
   *       logging it always had — {@code log.error} with the throwable attached, so the stack
   *       trace reaches the log in full — while the caller still gets a generic message, so
   *       internal detail never leaves through the API. A 5xx {@code ErrorResponse} is treated the
   *       same way, and is the one case that gains a stack trace it did not have before.
   * </ul>
   *
   * <p>What must stay true here: no condition in this method quiets an exception this application
   * did not expect. The counted branch is reachable only for an {@code ErrorResponse} whose status
   * Spring itself has already decided is a 4xx.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> unexpected(Exception exception) {
    if (exception instanceof ErrorResponse errorResponse) {
      HttpStatusCode status = errorResponse.getStatusCode();
      if (status.is4xxClientError()) {
        expectedErrors.record(status.value(), exception.getClass().getSimpleName());
      } else {
        log.error("Unhandled exception while serving a request", exception);
      }
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
