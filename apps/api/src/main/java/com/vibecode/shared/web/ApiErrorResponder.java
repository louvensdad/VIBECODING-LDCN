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
 *   <li><b>The body is omitted</b> when nothing the caller would accept can actually be written,
 *       because handing Spring a body Spring cannot write is the failure being prevented.
 *   <li><b>The omission is recorded</b> — as an expected client error for a 4xx, at DEBUG for a 5xx
 *       — so an operator can see that bodies are being dropped and why, without a server fault
 *       being filed as somebody else's mistake.
 * </ul>
 *
 * <p><b>"Can actually be written" is asked of the converters, not approximated.</b> The first
 * version of this class compared media types with {@code isCompatibleWith} against a list derived
 * from a parameterless {@code canWrite}, and both of those discard media-type <em>parameters</em>.
 * Spring's write path does not. So {@code Accept: application/json;charset=ISO-8859-1} — HTTP's own
 * historical default text charset, and a charset Jackson has no {@code JsonEncoding} for — was
 * promised a body, could not be given one, and escaped as a 500 on a real container. That was review
 * finding R3-A/R3-B, it was true of this check from the day it was written, and
 * {@link #callerAcceptsOurRepresentation()} carries the full account. It is also why this class
 * holds the converters and not only a list of types.
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

  /**
   * The converters themselves, kept rather than distilled into the list above.
   *
   * <p>Because the list above cannot answer the question that matters. It is derived from a
   * <b>parameterless</b> {@code canWrite(ApiError.class, null)}, and comparing against it uses
   * {@code isCompatibleWith}, which <b>ignores media-type parameters</b>. Spring's write path does
   * neither: it calls {@code canWrite(valueType, selectedMediaType)} with the caller's full media
   * type. So the list is the right thing to ask "is this type in the family we produce" and the
   * wrong thing to ask "will this actually get written" — see {@link #callerAcceptsOurRepresentation()}.
   *
   * <p><b>Review finding R4-B: whose converters these are.</b> They are the {@code
   * HttpMessageConverters} bean's, and that is <em>not</em> byte-for-byte the list {@code
   * RequestMappingHandlerAdapter} writes with. Measured: the adapter holds ten, this holds nine. The
   * extra one is {@code ProjectingJackson2HttpMessageConverter}, contributed by
   * {@code spring-data-commons}' own {@code WebMvcConfigurer} and prepended ahead of everything the
   * bean knows about. It answers {@code canWrite(ApiError, …)} false in every form, so the two lists
   * agree on the only question this class asks — today. A future {@code WebMvcConfigurer} adding a
   * <em>writing</em> converter through {@code extendMessageConverters} would be visible to Spring
   * and invisible here, which is R3-A's shape one layer out: the argument would be identical and the
   * receiver would not.
   *
   * <p>Injecting the adapter's list instead was tried and is not available. It is a hard circular
   * reference — {@code apiErrorResponder → requestMappingHandlerAdapter → apiExceptionHandler →
   * apiErrorResponder}, a {@code BeanCurrentlyInCreationException} that Boot prohibits by default —
   * because the adapter's construction reaches the advice that holds this class. Breaking it would
   * mean resolving the converters lazily on the request path, which trades a structural guarantee
   * for a timing one.
   *
   * <p>So the difference is stated rather than removed, and it is stated in a test rather than only
   * here: {@code ApiErrorResponderNegotiationTest} asserts that every converter in the adapter's
   * list which can write an {@link ApiError} is also in this one. That is the sentence with an
   * assertion under it, and it fails on exactly the future change described above.
   */
  private final List<HttpMessageConverter<?>> converters;

  ApiErrorResponder(ExpectedHttpErrorLog expectedErrors, HttpMessageConverters converters) {
    this.expectedErrors = expectedErrors;
    this.converters = List.copyOf(converters.getConverters());
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
   * Whether an {@link ApiError} can actually be written for this caller.
   *
   * <p>"Our representation" is whatever the converters say, not the string {@code
   * application/json}. Today the family resolves to {@code application/json} and {@code
   * application/*+json}, so {@code application/problem+json}, {@code application/hal+json} and any
   * vendor {@code +json} type keep their body. A missing or blank header states no preference, and
   * no preference accepts everything. An unparseable header answers false: Spring's own negotiation
   * cannot use it either, so the choice is between omitting the body deliberately and letting the
   * write fail, and the write failing is the defect being prevented.
   *
   * <p><b>Review finding R3-A/R3-B: why this asks the converters instead of comparing types.</b>
   * The first version of this check was
   *
   * <pre>
   *   acceptable.isCompatibleWith(writable)   // over a list derived from canWrite(ApiError.class, null)
   * </pre>
   *
   * <p>and both halves of that discard media-type <em>parameters</em>. Spring's write path does not:
   * it calls {@code converter.canWrite(valueType, selectedMediaType)} with the caller's full type,
   * and {@code getMostSpecificMediaType} prefers the caller's type precisely when it carries the
   * extra parameters. {@code AbstractJackson2HttpMessageConverter.canWrite} returns <b>false</b> for
   * any charset it has no {@code JsonEncoding} for. So this method said "write it", Spring said "no
   * acceptable representation", the write threw inside the {@code @ExceptionHandler} — which Spring
   * does not re-dispatch — and the exception escaped. Measured on a real container:
   * {@code Accept: application/json;charset=ISO-8859-1}, HTTP's own historical default text charset,
   * answered <b>500 with no body</b> on a request whose true answer was 400.
   *
   * <p>A {@code q} parameter had been measured against Spring and found genuinely harmless, and that
   * measurement was then generalised to every parameter. The property that makes {@code q} safe to
   * ignore is exactly the property that makes {@code charset} unsafe to ignore. So the decision is
   * no longer an approximation of what the converters will do — it is what they say, asked with the
   * same argument Spring will ask with.
   *
   * <p><b>Every candidate, not merely one.</b> Spring builds its candidate list, takes the
   * <b>first concrete</b> entry and asks the converters about that one only. A writable alternative
   * further along the header does not save the response if a compatible-but-unwritable type is
   * selected ahead of it, so a header like
   * {@code application/json;charset=ISO-8859-1, application/json} must answer false rather than bet
   * on which entry Spring picks. Candidacy is still {@code isCompatibleWith} against
   * {@link #writableErrorTypes}, deliberately: that mirrors how Spring decides which acceptable
   * types enter the list at all, so a type outside the family — {@code application/xml} beside a
   * plain {@code application/json} — cannot poison a header it was never going to be selected from.
   *
   * <p>The cost is a deliberate false negative on a mixed header Spring might have written: the body
   * is dropped and the status is untouched. Dropping a body is recoverable and recorded; an escaped
   * exception is neither.
   *
   * <p>Review sized that cost rather than leaving it as a shrug: <b>69 of 370 header shapes</b>,
   * 18.6%, every one of them in the safe direction, and in three families — a charset on a wildcard
   * media <em>range</em> (which means nothing, since a range names no representation to encode),
   * quality reordering that puts the writable entry second, and a preferred-charset-first pair. None
   * is a header a real client sends. A browser's own {@code Accept}, a plain {@code
   * application/json}, {@code &#42;/&#42;}, {@code application/*}, an absent header, a blank one and
   * {@code q=0} all keep their full body. Being wrong 18.6% of the time in the direction of "send
   * the status without a body" is the trade that was chosen; being wrong once in the direction of
   * "escape the resolver" is the one that was not.
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
    List<MediaType> acceptable;
    try {
      acceptable = MediaType.parseMediaTypes(accept);
    } catch (InvalidMediaTypeException malformed) {
      return false;
    }
    try {
      boolean anyCandidate = false;
      for (MediaType type : acceptable) {
        if (!isCandidate(type)) {
          continue;
        }
        anyCandidate = true;
        if (!someConverterCanWrite(type)) {
          return false;
        }
      }
      return anyCandidate;
    } catch (RuntimeException converterFailed) {
      // Review finding R4-A, and it is the one thing this defence itself introduced.
      //
      // Asking the converters is what made this check correct, but it also put third-party code on
      // the request path *inside an @ExceptionHandler* — the one place Spring will not re-dispatch.
      // Before this, no converter code ran here at all: the derivation happened once, in the
      // constructor. A converter whose canWrite throws would therefore have thrown out of the
      // handler and escaped the resolver, which is precisely the failure every line of this class
      // exists to prevent, reintroduced by the fix for it.
      //
      // No converter Spring or Boot ships can reach this — they are pure predicates — so this is
      // theoretical today. It is caught anyway, because the method two lines up already catches the
      // other way this can throw: an unparseable Accept header. Defending against one and not the
      // other is not a judgement about likelihood, it is an inconsistency, and an inconsistency is
      // what the next person reads as permission.
      //
      // False is the only safe answer. We asked whether a body can be written and got no usable
      // answer, so we do not promise one: the status stands and the omission is recorded, exactly
      // as for a header we could not parse.
      // At ERROR with the throwable, and that is not a lapse in a class built to keep traces out of
      // the log. A converter that throws from canWrite is a fault in this application, not a
      // caller's mistake, and this class's whole rule is that the two are told apart: an expected
      // client error is counted, a fault of ours keeps its trace. Swallowing this one silently
      // would hide a broken converter behind correct-looking 400s forever. It is also not
      // caller-paced volume — a converter that throws for one caller throws for all of them.
      log.error(
          "A message converter failed while being asked whether an error body can be written;"
              + " sending the status without a body",
          converterFailed);
      return false;
    }
  }

  /**
   * Whether Spring would put this acceptable type into the candidate list at all — the same
   * {@code acceptable.isCompatibleWith(producible)} test its write path uses to build that list.
   */
  private boolean isCandidate(MediaType acceptable) {
    for (MediaType writable : writableErrorTypes) {
      if (acceptable.isCompatibleWith(writable)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The question Spring's write path actually asks, asked with the same argument.
   *
   * <p>The quality value is removed first because Spring removes it before calling {@code canWrite},
   * and asking a different question than the one that will be asked is how this method came to be
   * wrong in the first place.
   */
  private boolean someConverterCanWrite(MediaType acceptable) {
    MediaType asked = acceptable.removeQualityValue();
    for (HttpMessageConverter<?> converter : converters) {
      if (converter.canWrite(ApiError.class, asked)) {
        return true;
      }
    }
    return false;
  }
}
