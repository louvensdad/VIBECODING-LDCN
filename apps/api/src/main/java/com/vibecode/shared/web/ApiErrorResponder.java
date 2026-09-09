package com.vibecode.shared.web;

import com.vibecode.shared.logging.ExpectedHttpErrorLog;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The one place that decides whether an {@link ApiError} can be written for the caller in front of
 * us, and what to send when it cannot.
 *
 * <p><b>Why this is a class of its own rather than a private method on {@link
 * ApiExceptionHandler}.</b> It was a private method there, and being private is what let FINDING
 * CTX-09B-3b exist. {@code ContextPackController.InvalidLimitAdvice} is a second
 * {@code @RestControllerAdvice} — it has to be, because the {@code limit} contract is scoped to one
 * controller and moving it into the shared advice would change the error contract of every module
 * in this application — and it could not reach the decision, so it built
 * {@code ResponseEntity.status(BAD_REQUEST).body(...)} itself. Under {@code Accept:
 * application/xml} that body cannot be serialised, the write fails <em>inside</em> the exception
 * handler, Spring does not re-dispatch, and the original exception escapes the resolver: a 400
 * became a 500 on a real container. Two pieces of work that were each correct alone; the defect
 * lived only in the seam between them.
 *
 * <p>So the boundary is now a collaborator that both advices hold, and the number of places that
 * decide this stayed at one. That is the property to preserve: <b>no {@code @ExceptionHandler}
 * anywhere in this application may build an error body without going through {@link #respond}.</b>
 * A second mechanism for one endpoint is how the first one came to be wrong.
 *
 * <p>The decision itself is unchanged from the one {@link ApiExceptionHandler} already made, and
 * deliberately so — the fix was to share the existing answer, not to invent a second one for a
 * single route:
 *
 * <ul>
 *   <li><b>The status is kept.</b> An {@code Accept} header is a statement about representations,
 *       not about what happened. Answering 406 instead would throw away the fact that the request
 *       was also, say, unauthorised, and would let a header the caller chooses decide what this API
 *       reports.
 *   <li><b>The body is omitted</b> when the caller accepts nothing we write, because handing Spring
 *       a body Spring cannot write is the failure being prevented.
 *   <li><b>The omission is recorded</b> — as an expected client error for a 4xx, at DEBUG for a 5xx
 *       — so an operator can see that bodies are being dropped and why, without a server fault
 *       being filed as somebody else's mistake.
 * </ul>
 */
@Component
public class ApiErrorResponder {

  private static final Logger log = LoggerFactory.getLogger(ApiErrorResponder.class);

  /**
   * The tally key for an error body dropped because the caller accepts none of our media types.
   *
   * <p>A fixed string, like every other kind {@link ExpectedHttpErrorLog} records. A caller chooses
   * whether it is reached; they do not get to choose what it says.
   */
  static final String BODY_OMITTED = "BodyOmittedForAcceptHeader";

  private final ExpectedHttpErrorLog expectedErrors;

  /**
   * The media types some converter in this application can actually write an {@link ApiError} as.
   *
   * <p>Asked of the converters rather than written down as a literal, and that is the whole point
   * of this field. The first version of the {@code Accept} check compared against {@code
   * application/json} alone, which is not what Jackson advertises: it also writes {@code
   * application/*+json}, so Spring had always been serving {@code application/problem+json} — RFC
   * 7807, the header an error-aware client is most likely to send — along with {@code
   * application/hal+json} and every vendor {@code +json} type. Comparing against the one literal
   * silently dropped the body for all of them. That was a narrowing of the error contract nobody
   * declared, found in review, and the reason this is derived rather than asserted.
   *
   * <p>Derived once at construction because the converter list is fixed after the context is built,
   * and derived from {@code canWrite(ApiError.class, null)} so that a converter added, replaced or
   * reconfigured later is followed automatically instead of being missed. If a converter for
   * another representation is ever added, error bodies start being written in it without this class
   * being touched — which is the correct behaviour and the reason not to hard-code a list.
   *
   * <p>{@code ApiExceptionHandlerTest} pins what this resolves to today, so that a converter change
   * which widened it to a wildcard — quietly turning the guard below into a no-op and bringing back
   * the defect this infrastructure exists to prevent — fails the build rather than passing
   * unnoticed.
   */
  private final List<MediaType> writableErrorTypes;

  ApiErrorResponder(ExpectedHttpErrorLog expectedErrors, HttpMessageConverters converters) {
    this.expectedErrors = expectedErrors;
    this.writableErrorTypes = writableErrorTypesOf(converters);
  }

  private static List<MediaType> writableErrorTypesOf(HttpMessageConverters converters) {
    List<MediaType> types = new ArrayList<>();
    for (HttpMessageConverter<?> converter : converters.getConverters()) {
      if (converter.canWrite(ApiError.class, null)) {
        for (MediaType supported : converter.getSupportedMediaTypes(ApiError.class)) {
          if (!types.contains(supported)) {
            types.add(supported);
          }
        }
      }
    }
    return List.copyOf(types);
  }

  /** What this instance decided it can write. Package-private so a test can pin it. */
  List<MediaType> writableErrorTypes() {
    return writableErrorTypes;
  }

  /**
   * Every error body in this application leaves through here, and it leaves without the body when
   * the caller cannot read it.
   *
   * <p>Spring does not re-dispatch an exception thrown while writing an {@code @ExceptionHandler}'s
   * own return value: it logs {@code Failure in @ExceptionHandler} at WARN with the throwable and
   * returns null, and the original exception then falls through to whatever else will take it. So a
   * mapped error whose {@code ApiError} could not be serialised cost 190 WARN frames <em>and</em>
   * lost its own response.
   *
   * <p>Measured on a real Tomcat rather than reasoned about, because the consequence is not a
   * logging one. {@code GET /api/projects/<unknown id>} with {@code Accept: application/xml}
   * answered <b>500 with no body</b>, where the same request answered 404 with the documented
   * {@code NOT_FOUND} body under any other Accept header. A header the caller chooses was changing
   * the status this API reports. {@code GET /api/projects/{id}/context?limit=0} did exactly the
   * same thing for as long as one advice built its body without asking here, which is the whole
   * reason this method is reachable from outside its own class.
   */
  public ResponseEntity<ApiError> respond(HttpStatusCode status, ApiError body) {
    if (callerAcceptsOurRepresentation()) {
      return ResponseEntity.status(status).body(body);
    }
    if (status.is4xxClientError()) {
      expectedErrors.record(status.value(), BODY_OMITTED);
    } else {
      // A 5xx whose body could not be written is not an expected client error, and calling it one
      // would repeat — one level down — the mislabelling that filing a server fault as somebody
      // else's problem always is. The fault itself has already been logged at ERROR with its
      // throwable by whichever handler produced this status; all that is left to say is that the
      // body went unsent, and that is a DEBUG line rather than a tally of somebody else's mistake.
      log.debug(
          "Error body omitted for an unacceptable Accept header on a {} response", status.value());
    }
    return ResponseEntity.status(status).build();
  }

  /**
   * Whether the caller will accept the one representation this API produces.
   *
   * <p>"Our representation" is whatever the converters say they can write an {@link ApiError} as,
   * not the string {@code application/json}. Today that resolves to {@code application/json} and
   * {@code application/*+json}, so {@code application/problem+json}, {@code application/hal+json}
   * and any vendor {@code +json} type keep their body — as they did before this infrastructure
   * existed, and as review found they had stopped doing when this compared against one literal. A
   * missing or blank header states no preference, and no preference accepts everything.
   *
   * <p>An unparseable header answers false. Spring's own negotiation cannot use it either, so the
   * choice is between omitting the body deliberately and letting the write fail — and the write
   * failing is the defect being prevented.
   */
  public boolean callerAcceptsOurRepresentation() {
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
        for (MediaType writable : writableErrorTypes) {
          if (acceptable.isCompatibleWith(writable)) {
            return true;
          }
        }
      }
      return false;
    } catch (InvalidMediaTypeException malformed) {
      return false;
    }
  }
}
