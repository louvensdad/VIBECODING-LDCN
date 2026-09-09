package com.vibecode.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * The responder's answer, put next to the converters' answer, header by header.
 *
 * <p><b>Why this test exists (review finding R3-A/R3-B).</b> The {@code Accept} check was built out
 * of two things that both discard media-type parameters:
 *
 * <pre>
 *   converter.canWrite(ApiError.class, null)     // the writable list, derived parameterless
 *   acceptable.isCompatibleWith(writable)        // the comparison, which ignores parameters
 * </pre>
 *
 * <p>Spring's write path does neither. It calls {@code converter.canWrite(valueType,
 * selectedMediaType)} with the caller's <em>full</em> media type, and {@code
 * getMostSpecificMediaType} prefers the caller's type precisely when it carries the extra
 * parameters. {@code AbstractJackson2HttpMessageConverter.canWrite} then answers <b>false</b> for
 * any charset it has no {@code JsonEncoding} for. So the responder said "write it", Spring said "no
 * acceptable representation", the write threw inside the {@code @ExceptionHandler}, Spring did not
 * re-dispatch, and the exception escaped — the original defect, reached through a different door.
 * {@code ISO-8859-1} is HTTP's own historical default text charset, not an exotic input.
 *
 * <p>That was true at {@code e492f51} too: the relocated code is token-identical, so this is a hole
 * in the {@code Accept} check as first written, not a regression introduced by moving it.
 *
 * <p><b>What this test asserts, and why it is a table rather than a rule.</b> The responder's
 * decision and the converters' decision must agree on every header. Not "the responder is
 * conservative", not "the charsets we thought of are handled" — <em>agree</em>, on inputs chosen to
 * straddle the boundary, with the five charsets Jackson accepts on one side and five it does not on
 * the other. A rule stated in prose is what produced the defect; a rule the converters are asked
 * for cannot drift from them.
 *
 * <p><b>The generalisation this file is really about.</b> A {@code q} parameter was measured
 * against Spring and found harmless — {@code isCompatibleWith} ignores it and so does Spring's own
 * selection. That measurement was correct, and it was then generalised to <em>all</em> parameters,
 * which is where it went wrong: the very property that makes {@code q} safe to ignore is the
 * property that makes {@code charset} unsafe to ignore. One parameter was verified and every
 * parameter was concluded. Hence a table over parameters rather than a claim about them.
 */
@SpringBootTest
class ApiErrorResponderNegotiationTest {

  @Autowired ApiErrorResponder responder;
  @Autowired HttpMessageConverters converters;
  @Autowired RequestMappingHandlerAdapter adapter;
  @Autowired com.vibecode.shared.logging.ExpectedHttpErrorLog tally;

  /**
   * Every header this test knows about, and whether some converter will actually write an
   * {@link ApiError} as it. The expectation column is not hard-coded from this list: it is asked of
   * the converters at run time, so a Jackson version that widened or narrowed its charset support
   * moves both columns together instead of turning this red for the wrong reason.
   */
  private static final List<String> HEADERS =
      List.of(
          // Jackson has a JsonEncoding for these five.
          "application/json;charset=UTF-8",
          "application/json;charset=US-ASCII",
          "application/json;charset=UTF-16BE",
          "application/json;charset=UTF-16LE",
          "application/json;charset=UTF-32BE",
          // And none for these five. The first is HTTP's own historical default.
          "application/json;charset=ISO-8859-1",
          "application/json;charset=UTF-16",
          "application/json;charset=UTF-32",
          "application/json;charset=windows-1252",
          "application/json;charset=Shift_JIS",
          // The +json family has to follow the same rule, or the fix is charset-aware for one
          // spelling of JSON and not for the RFC 7807 one an error-aware client sends.
          "application/problem+json;charset=UTF-8",
          "application/problem+json;charset=ISO-8859-1",
          // Parameters that are not charset must still be ignored, which is the half that was right.
          "application/json;q=0",
          "application/json;q=0.9",
          "application/json;charset=UTF-8;q=0.9");

  @Test
  @DisplayName("R3-A: the responder's decision agrees with the converters on every media type")
  void theDecisionIsTheConvertersAnswerAndNotAnApproximationOfIt() {
    Map<String, String> table = new LinkedHashMap<>();
    Map<String, String> expected = new LinkedHashMap<>();

    for (String header : HEADERS) {
      boolean decided = decisionFor(header);
      boolean writable = someConverterCanWrite(header);
      table.put(header, "responder=" + decided + " converters=" + writable);
      expected.put(header, "responder=" + writable + " converters=" + writable);
    }

    assertThat(table)
        .as(
            "R3-A: the responder may not promise a body the converters will refuse to write. Every"
                + " disagreement here is an exception escaping an @ExceptionHandler, which Spring"
                + " does not re-dispatch, which is a 500 for a client error on a real container.")
        .containsExactlyInAnyOrderEntriesOf(expected);

    // Non-vacuity, and it is the whole point of the table: both answers must actually occur. If
    // every converter said yes to everything, agreement would be free and this test would be
    // decoration. Five of the fifteen rows above are refusals.
    assertThat(table.values().stream().filter(v -> v.endsWith("converters=false")).count())
        .as("the boundary this table straddles must have inputs on both sides of it")
        .isGreaterThanOrEqualTo(5);
    assertThat(table.values().stream().filter(v -> v.endsWith("converters=true")).count())
        .isGreaterThanOrEqualTo(5);
  }

  /**
   * The header shapes that carry no parameters, kept passing, because the fix must not narrow what
   * was already right.
   *
   * <p>A charset-aware check that answered "no body" to {@code &#42;/&#42;} or to a plain {@code
   * application/json} would trade one defect for its mirror image — dropping error bodies from the
   * ordinary caller, which no status assertion in this suite would notice.
   */
  @Test
  @DisplayName("The parameterless headers keep the answers they already had")
  void theOrdinaryHeadersAreUnaffected() {
    Map<String, Boolean> expected = new LinkedHashMap<>();
    expected.put("application/json", true);
    expected.put("application/problem+json", true);
    expected.put("application/vnd.vibecode.v1+json", true);
    expected.put("*/*", true);
    expected.put("application/*", true);
    expected.put("APPLICATION/JSON", true);
    expected.put("application/xml", false);
    expected.put("text/*", false);
    expected.put("this is not a media type", false);

    Map<String, Boolean> actual = new LinkedHashMap<>();
    expected.keySet().forEach(header -> actual.put(header, decisionFor(header)));

    assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);

    // A missing header states no preference, and no preference accepts everything.
    assertThat(decisionFor(null)).isTrue();
    assertThat(decisionFor("  ")).isTrue();
  }

  /**
   * A list whose first entry is unwritable and whose second is writable, which is the case that
   * decides how strict the check has to be.
   *
   * <p>Spring does not try every acceptable type. It builds the candidate list, takes the
   * <b>first concrete</b> one, and asks the converters about that one only — so a writable
   * alternative further down the header does not save the response if a compatible-but-unwritable
   * type is selected ahead of it. The check is therefore "every candidate is writable", not "some
   * candidate is writable", and this row is why. The cost is a deliberate false negative: the body
   * is dropped for a header Spring might have written, the status is untouched, and dropping a body
   * is recoverable where an escaped exception is not.
   */
  @Test
  @DisplayName("A writable alternative behind an unwritable one does not make the write safe")
  void aMixedListIsRefusedRatherThanGambledOn() {
    assertThat(decisionFor("application/json;charset=ISO-8859-1, application/json"))
        .as(
            "Spring selects one candidate and asks about that one; a check that answered yes here"
                + " would be betting on which one it picks")
        .isFalse();
    // And the mirror: an unwritable type that is not a candidate at all cannot poison the list,
    // because it never enters Spring's selection either.
    assertThat(decisionFor("application/xml, application/json"))
        .as("application/xml is not compatible with anything we produce, so it is not a candidate")
        .isTrue();
  }

  /**
   * Review finding R4-B: the responder asks the right question of a list that is not quite Spring's.
   *
   * <p>The argument is now identical to the one Spring's write path uses. The <b>receiver</b> is
   * not: this class holds the {@code HttpMessageConverters} bean's converters, and {@code
   * RequestMappingHandlerAdapter} writes with its own list, which a {@code WebMvcConfigurer} can
   * extend. Measured today: the adapter holds one more —
   * {@code ProjectingJackson2HttpMessageConverter}, prepended by {@code spring-data-commons} — and
   * it cannot write an {@link ApiError}, so the two agree on the only question asked.
   *
   * <p>Injecting the adapter's list would remove the difference and cannot be done: it is a hard
   * circular reference, measured, because the adapter's construction reaches the advice that holds
   * the responder. So the difference is pinned instead, and this is the assertion that would fail if
   * someone added a writing converter through {@code extendMessageConverters} — R3-A's shape one
   * layer out, and the only way this class can go wrong again in the same manner.
   *
   * <p>The property is deliberately one-directional. The adapter having converters the responder
   * lacks is the dangerous direction — Spring would write in a type the responder never considered.
   * The reverse is harmless: a converter the responder knows about and Spring does not can only make
   * the responder more permissive about a type nothing will be asked to write, which the
   * per-candidate check then refuses anyway.
   */
  @Test
  @DisplayName("R4-B: every converter Spring would write an ApiError with is one the responder holds")
  void theResponderSeesEveryConverterThatCouldWriteAnError() {
    List<String> adapterCanWrite =
        adapter.getMessageConverters().stream()
            .filter(converter -> converter.canWrite(ApiError.class, null))
            .map(converter -> converter.getClass().getName())
            .sorted()
            .toList();
    List<String> responderHolds =
        converters.getConverters().stream()
            .filter(converter -> converter.canWrite(ApiError.class, null))
            .map(converter -> converter.getClass().getName())
            .sorted()
            .toList();

    assertThat(adapterCanWrite)
        .as(
            "a converter Spring will write an error body with, that this class has never been shown,"
                + " is R3-A one layer out: the right question asked of the wrong receiver")
        .containsExactlyElementsOf(responderHolds);

    // Non-vacuity: both sides must actually contain something, or "they agree" is a statement about
    // two empty lists. And the known, harmless asymmetry is pinned by name so that it changing is
    // visible rather than silently absorbed into the assertion above.
    assertThat(adapterCanWrite).isNotEmpty();
    assertThat(adapter.getMessageConverters().size())
        .as("the adapter's list is the larger one, and this is the measurement that says so")
        .isGreaterThanOrEqualTo(converters.getConverters().size());
  }

  /**
   * Review finding R4-C: {@code removeQualityValue()} is correct, and until now nothing asserted it.
   *
   * <p>Removing that call left twenty-two tests across three classes green. It is the right line —
   * Spring strips the quality value before calling {@code canWrite}, so asking with it is asking a
   * different question than the one that will be asked — but its absence could only ever produce a
   * false negative, and no existing test could see one. Jackson's {@code canWrite} inspects
   * {@code getCharset()} and nothing else, so {@code q} is invisible to every converter this
   * application ships. A claim with no assertion under it is the thing this whole task has been
   * about, so the claim is now measured directly: not through a converter that happens to care about
   * {@code q}, but by recording the media type the responder actually passes.
   *
   * <p>Built by hand rather than autowired, so that adding a recording converter cannot disturb the
   * application context or the {@code writableErrorTypes} pin that {@code ApiExceptionHandlerTest}
   * holds.
   */
  @Test
  @DisplayName("R4-C: the converters are asked with the quality value stripped, as Spring asks")
  void theConvertersAreAskedWithTheQualityValueRemoved() {
    RecordingConverter recorder = new RecordingConverter();
    ApiErrorResponder isolated =
        new ApiErrorResponder(
            new com.vibecode.shared.logging.ExpectedHttpErrorLog(),
            new HttpMessageConverters(
                false,
                List.of(recorder, new MappingJackson2HttpMessageConverter())));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.ACCEPT, "application/json;q=0.5, application/problem+json;q=0.25");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      isolated.callerAcceptsOurRepresentation();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }

    assertThat(recorder.asked)
        .as("the recorder must actually have been consulted, or there is nothing to inspect")
        .isNotEmpty();
    assertThat(recorder.asked)
        .as(
            "R4-C: Spring removes the quality value before calling canWrite, and asking a different"
                + " question than the one that will be asked is how this method came to be wrong in"
                + " the first place")
        .allSatisfy(asked -> assertThat(asked.getParameter("q")).isNull());
    // And the rest of the media type survives intact, so this is stripping q rather than stripping
    // parameters — which would undo the R3-A fix entirely.
    assertThat(recorder.asked.stream().map(MediaType::toString).toList())
        .containsExactly("application/json", "application/problem+json");
  }

  /** Records every media type it is asked about, and can write nothing. */
  private static final class RecordingConverter implements HttpMessageConverter<ApiError> {

    private final List<MediaType> asked = new java.util.ArrayList<>();

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
      return false;
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
      if (mediaType != null) {
        asked.add(mediaType);
      }
      return false;
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
      return List.of();
    }

    @Override
    public ApiError read(Class<? extends ApiError> clazz, org.springframework.http.HttpInputMessage in) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void write(ApiError body, MediaType contentType, org.springframework.http.HttpOutputMessage out) {
      throw new UnsupportedOperationException();
    }
  }

  private boolean decisionFor(String accept) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (accept != null) {
      request.addHeader(HttpHeaders.ACCEPT, accept);
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      return responder.callerAcceptsOurRepresentation();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  /** The question Spring's write path actually asks, asked the same way. */
  private boolean someConverterCanWrite(String accept) {
    MediaType type = MediaType.parseMediaType(accept).removeQualityValue();
    for (HttpMessageConverter<?> converter : converters.getConverters()) {
      if (converter.canWrite(ApiError.class, type)) {
        return true;
      }
    }
    return false;
  }
}
