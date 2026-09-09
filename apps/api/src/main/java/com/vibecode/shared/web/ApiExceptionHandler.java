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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Produces one consistent error body for the whole API. */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /**
   * The tally key for an error body dropped because the caller accepts none of our media types.
   *
   * <p>A fixed string, like every other kind this class records. A caller chooses whether it is
   * reached; they do not get to choose what it says.
   */
  private static final String BODY_OMITTED = "BodyOmittedForAcceptHeader";

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
    return respond(
        HttpStatus.BAD_REQUEST,
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
    return respond(
        HttpStatus.BAD_REQUEST,
        ApiError.of(
            HttpStatus.BAD_REQUEST.value(),
            "VALIDATION_ERROR",
            "The request could not be read.",
            List.of(
                new ApiError.FieldViolation(
                    exception.getName(), "is not a valid value for this parameter"))));
  }

  /**
   * An {@code Accept} header naming nothing this API can produce — and the case that looks like
   * it and is not.
   *
   * <p>Spring raises one exception type for two situations that are opposites, and the first
   * version of this branch treated them as one. Review found that, and it was the more serious of
   * the two findings against it.
   *
   * <ul>
   *   <li><b>The caller accepts none of our types.</b> Their mistake, entirely under their control,
   *       and there is no body we could send that they would take. Counted, answered with the
   *       status alone, no trace.
   *   <li><b>The caller accepts JSON and we still could not write a representation.</b> Then the
   *       fault is ours — a return type no converter claims, or a {@code produces} clause that
   *       contradicts what the route can build. Nothing about that is the caller's doing, and
   *       filing it as an expected client error would be exactly the mislabelling this task was
   *       written to avoid: a server fault tallied at DEBUG as somebody else's problem. It keeps
   *       the body it had before this task touched the class — read from the exception's own
   *       {@code ErrorResponse} title, so it is the same string rather than a copy of it — and
   *       it now also writes an ERROR with the throwable, which it never had.
   * </ul>
   *
   * <p>The ERROR side is not caller-reachable, which is what makes it safe to trace in full: a
   * caller cannot choose a controller's return type. The one route in from outside would be a
   * mapping declaring {@code produces} for a type it cannot build, and no route in this API
   * declares {@code produces} at all. If one ever does, this branch will trace on a request the
   * caller controls, and that is the condition to re-examine — recorded here rather than
   * guarded against, because guarding against it today would mean inventing a distinction with
   * nothing on either side of it.
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<ApiError> notAcceptable(HttpMediaTypeNotAcceptableException exception) {
    if (!callerAcceptsOurRepresentation()) {
      expectedErrors.record(HttpStatus.NOT_ACCEPTABLE.value(), exception.getClass().getSimpleName());
      return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }
    log.error(
        "No representation could be written for a caller that accepts this API's media type",
        exception);
    return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
        .body(
            ApiError.of(
                HttpStatus.NOT_ACCEPTABLE.value(), "BAD_REQUEST", exception.getBody().getTitle()));
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
      return respond(
          status,
          ApiError.of(
              status.value(),
              status.is4xxClientError() ? "BAD_REQUEST" : "INTERNAL_ERROR",
              errorResponse.getBody().getTitle()));
    }
    log.error("Unhandled exception while serving a request", exception);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected internal error.");
  }

  private ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
    return respond(status, ApiError.of(status.value(), code, message));
  }

  /**
   * Every error body in this class leaves through here, and it leaves without the body when the
   * caller cannot read it.
   *
   * <p>This is the half of the {@code Accept} defect that the branch above does not reach, and it
   * is the more damaging half. Spring does not re-dispatch an exception thrown while writing an
   * {@code @ExceptionHandler}'s own return value: it logs {@code Failure in @ExceptionHandler} at
   * WARN with the throwable and returns null, and the original exception then falls through to
   * whatever else will take it. So a mapped error whose {@code ApiError} could not be serialised
   * cost 190 WARN frames <em>and</em> lost its own response.
   *
   * <p>Measured on a real Tomcat rather than reasoned about, because the consequence is not a
   * logging one. {@code GET /api/projects/<unknown id>} with {@code Accept: application/xml}
   * answered <b>500 with no body</b>, where the same request answered 404 with the documented
   * {@code NOT_FOUND} body under any other Accept header. A header the caller chooses was changing
   * the status this API reports. That was already true before this task began and is not a
   * regression; it is the reason this helper exists rather than another {@code @ExceptionHandler}
   * chasing one more exception type.
   *
   * <p>What it does is refuse to hand Spring a body Spring cannot write. The status is kept,
   * because the status is the true answer and a caller's {@code Accept} header is a statement
   * about representations rather than about what happened; answering 406 instead would throw away
   * the fact that the request was also, say, unauthorised. The omission is recorded as an expected
   * client error, so an operator can see that bodies are being dropped and why.
   */
  private ResponseEntity<ApiError> respond(HttpStatusCode status, ApiError body) {
    if (callerAcceptsOurRepresentation()) {
      return ResponseEntity.status(status).body(body);
    }
    expectedErrors.record(status.value(), BODY_OMITTED);
    return ResponseEntity.status(status).build();
  }

  /**
   * Whether the caller will accept the one representation this API produces.
   *
   * <p>{@code application/json} is that representation everywhere: no route declares
   * {@code produces}, and no converter for any other type is on the classpath — which is
   * precisely why {@code Accept: application/xml} fails at all. A missing or blank header states no
   * preference, and no preference accepts everything.
   *
   * <p>An unparseable header answers false. Spring's own negotiation cannot use it either, so the
   * choice is between omitting the body deliberately and letting the write fail — and the
   * write failing is the defect being fixed.
   */
  private static boolean callerAcceptsOurRepresentation() {
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes)) {
      // Not a servlet dispatch at all. Nothing is being negotiated, so nothing is being refused.
      return true;
    }
    String accept = attributes.getRequest().getHeader(HttpHeaders.ACCEPT);
    if (accept == null || accept.isBlank()) {
      return true;
    }
    try {
      for (MediaType acceptable : MediaType.parseMediaTypes(accept)) {
        if (acceptable.isCompatibleWith(MediaType.APPLICATION_JSON)) {
          return true;
        }
      }
      return false;
    } catch (InvalidMediaTypeException malformed) {
      return false;
    }
  }
}
