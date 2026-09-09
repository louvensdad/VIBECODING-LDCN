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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
