package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.domain.User;
import com.vibecode.support.TestIdentity;
import com.vibecode.support.logging.LoggerLevelIsolation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What an expected HTTP error costs the log, and what an unexpected one still costs it.
 *
 * <p>Both halves are here on purpose, and neither is meaningful alone. Showing that the noise from
 * {@code Accept: application/xml} is gone proves nothing about whether it was fixed or gagged: a
 * blanket filter, a raised level and a catch-all that swallowed everything would all produce the
 * same green. So every claim about silence in this class is paired with a claim about a genuine
 * server fault, made in the same run, through the same capture, with the same helpers.
 *
 * <p>The probe controller at the bottom is registered only in this test's context. It exists
 * because the application has no route that fails unexpectedly, which is the right property for the
 * application to have and an impossible one to test against. Its {@code /boom} route throws an
 * exception no handler in {@code ApiExceptionHandler} claims, which is exactly the shape of the
 * real 500 this change must not have quieted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(LoggerLevelIsolation.class)
class ExpectedHttpErrorLoggingTest {

  /**
   * Caller-supplied text, carried in the request body of every probe below.
   *
   * <p>In the body rather than in the URL deliberately. Spring logs the query string at DEBUG under
   * its own categories, so a fixture in a query parameter would be found by this test for a reason
   * that has nothing to do with error handling, and application.yml records that no route in this
   * API puts free text in a parameter. The body is the route a real secret actually travels.
   */
  private static final String FIXTURE = "vc_expected_http_error_secret_512477";

  private static final String NOT_ACCEPTABLE_KIND = "HttpMediaTypeNotAcceptableException";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TestIdentity identity;
  @Autowired ExpectedHttpErrorLog expectedErrors;

  private User caller;

  @BeforeEach
  void signIn() {
    caller = identity.createUser("expected-http-error-caller");
  }

  // ---------------------------------------------------------------- the defect, both directions

  @Test
  @DisplayName("A hundred unacceptable Accept headers: a hundred 406s, no secret, no stack trace")
  void aHundredUnacceptableRequestsCostNoStackTrace() {
    long countedBefore = expectedErrors.countOf(406, NOT_ACCEPTABLE_KIND);
    List<Integer> statuses = new ArrayList<>();

    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> {
              for (int i = 0; i < 100; i++) {
                statuses.add(echo(MediaType.APPLICATION_XML).getResponse().getStatus());
              }
            });

    assertThat(statuses).hasSize(100).containsOnly(406);

    // No secret, read through the chain-walking renderer rather than off the formatted message:
    // the value that leaks is as often inside a throwable as in an argument, and reading
    // String.valueOf(getThrowableProxy()) is the mistake that once made a check of this exact
    // shape pass while the appender was writing the value in full.
    assertThat(LogCapture.occurrences(events, FIXTURE)).isEmpty();

    // No stack trace, counted as frames rather than as events, so a one-frame throwable would not
    // slip past an assertion that only asked whether some trace was long enough to notice.
    assertThat(framesAtOrAbove(events, Level.WARN))
        .as("a header the caller sets must not write stack frames an operator has to read")
        .isEqualTo(0);
    assertThat(operatorVisibleTraces(events)).isEmpty();

    // And the signal that replaced it. This is also the non-vacuity control for everything above,
    // and it is a real one: these hundred DEBUG lines come from ExpectedHttpErrorLog itself, one
    // per recorded error, and nothing in Spring or Hibernate can produce them. An assertion that
    // the capture is merely non-empty would be satisfied by framework chatter alone and would say
    // nothing about whether the requests reached the handler at all.
    assertThat(linesFrom(events, ExpectedHttpErrorLog.class.getName(), Level.DEBUG))
        .as("one counted line per expected error, so the hundred requests are known to have run")
        .hasSize(100);
    assertThat(expectedErrors.countOf(406, NOT_ACCEPTABLE_KIND) - countedBefore).isEqualTo(100);

    // The summarised half: WARN volume follows the logarithmic schedule, so a hundred requests
    // announce a handful of times however many of them arrive.
    assertThat(linesFrom(events, ExpectedHttpErrorLog.class.getName(), Level.WARN).size())
        .as("the announced summary is bounded, not one line per request")
        .isLessThanOrEqualTo(3);
  }

  @Test
  @DisplayName("A real 500 still writes its trace, in the same suite that shows the 406 writing none")
  void anUnexpectedServerFaultIsStillFullyObservable() {
    List<ILoggingEvent> expectedRun =
        LogCapture.capturing(
            () ->
                assertThat(echo(MediaType.APPLICATION_XML).getResponse().getStatus())
                    .isEqualTo(406));
    List<ILoggingEvent> unexpectedRun =
        LogCapture.capturing(() -> assertThat(boom().getResponse().getStatus()).isEqualTo(500));

    // Same helper, same threshold, same run. One is silent and the other is not, and that
    // difference is what separates a fix from a gag.
    assertThat(framesAtOrAbove(expectedRun, Level.WARN))
        .as("the expected client error writes no frames")
        .isEqualTo(0);
    assertThat(framesAtOrAbove(unexpectedRun, Level.WARN))
        .as("an unexpected server fault must still write a full stack trace")
        .isGreaterThan(20);

    List<String> errors =
        linesFrom(unexpectedRun, "com.vibecode.shared.web.ApiExceptionHandler", Level.ERROR);
    assertThat(errors)
        .as("the handler must still report an unhandled exception at ERROR")
        .hasSize(1);
    assertThat(errors.get(0))
        .contains("Unhandled exception while serving a request")
        .contains("UnsupportedOperationException");

    // The ERROR line carries the exception, so an operator gets the type and the frames. It must
    // still not carry what the caller sent.
    assertThat(LogCapture.occurrences(unexpectedRun, FIXTURE)).isEmpty();

    // The 500 was never counted as an expected client error. Had it been, the class that keeps the
    // noise down would be the class hiding the fault.
    assertThat(expectedErrors.countOf(500, "UnsupportedOperationException")).isZero();
  }

  @Test
  @DisplayName("The success path through the same route logs neither the secret nor a trace")
  void theSuccessPathIsProbedToo() {
    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> {
              MvcResult result = echo(MediaType.APPLICATION_JSON);
              assertThat(result.getResponse().getStatus()).isEqualTo(200);
              try {
                // The value really did travel the whole way through: it comes back in the
                // response. A probe that only sent it would be green against a route that
                // silently dropped it, and would then be measuring nothing.
                assertThat(result.getResponse().getContentAsString()).contains(FIXTURE);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    assertThat(LogCapture.occurrences(events, FIXTURE)).isEmpty();
    assertThat(framesAtOrAbove(events, Level.WARN)).isEqualTo(0);
    assertThat(events)
        .as("no request was dispatched, so this capture proves nothing about the web layer")
        .anyMatch(event -> event.getLoggerName().startsWith("org.springframework.web"));
  }

  // ---------------------------------------------------------------- the contract, left alone

  /**
   * The error contract, pinned separately for the two kinds of caller — which is the correction
   * review asked for.
   *
   * <p>The first version of this test pinned {@code 406 <empty body>} unconditionally, and that
   * single unqualified entry is why nothing here caught the case where a caller who accepts JSON
   * perfectly well lost their body too. A contract that depends on the {@code Accept} header has to
   * be pinned once per header, or the pin is stating something narrower than it appears to.
   *
   * <p>Two headers were not enough either, and review found the gap: {@code application/json} and
   * {@code application/xml} say nothing about {@code application/problem+json}, which Jackson
   * writes, which Spring had always served, and which a check comparing against one literal media
   * type silently stopped serving. So the census now runs four headers over the same six
   * situations, and the two that must keep their bodies are asserted to be identical to each other
   * rather than merely non-empty.
   *
   * <ul>
   *   <li>{@code application/json} and {@code application/problem+json} — the body and code are
   *       exactly what they were before this task began.
   *   <li>{@code application/xml} and {@code text/html} — the status is the same and the body is
   *       absent, <b>which is the declared change</b>. What those requests produced before was not
   *       a body: 190 WARN frames, and then either a container 500 in place of the mapped status or
   *       the status with an empty body, depending on whether some other resolver happened to claim
   *       the exception. Both measured; the 500 is pinned on the wire in
   *       {@link ExpectedHttpErrorWireContractTest}, and so is the {@code +json} case.
   * </ul>
   */
  @Test
  @DisplayName("The error contract, pinned once per Accept header a caller might realistically send")
  void theClientErrorContractIsUnchanged() throws Exception {
    Map<String, String> jsonCaller = census(MediaType.APPLICATION_JSON);
    Map<String, String> problemJsonCaller = census(MediaType.APPLICATION_PROBLEM_JSON);
    Map<String, String> xmlCaller = census(MediaType.APPLICATION_XML);
    Map<String, String> htmlCaller = census(MediaType.TEXT_HTML);

    Map<String, String> expectedForJson = new LinkedHashMap<>();
    expectedForJson.put("400 malformed body", "400 MALFORMED_REQUEST");
    expectedForJson.put("400 validation", "400 VALIDATION_ERROR");
    expectedForJson.put("404 unknown route", "404 BAD_REQUEST");
    expectedForJson.put("405 method not allowed", "405 BAD_REQUEST");
    expectedForJson.put("415 unsupported media type", "415 BAD_REQUEST");
    expectedForJson.put("422 domain guard", "422 INVALID_STATE");
    assertThat(jsonCaller)
        .as("a caller who accepts JSON must see exactly the contract that existed before")
        .containsExactlyInAnyOrderEntriesOf(expectedForJson);

    // RFC 7807, the header an error-aware client is most likely to send. Jackson advertises
    // application/*+json, so Spring served this in full before this task, and the first version of
    // the Accept check stopped it doing so. Asserted against the JSON census rather than against a
    // second copy of the literals, so the two cannot drift apart.
    assertThat(problemJsonCaller)
        .as("a +json caller accepts a representation we can write, and must get the whole body")
        .containsExactlyInAnyOrderEntriesOf(jsonCaller);

    Map<String, String> expectedForXml = new LinkedHashMap<>();
    expectedForXml.put("400 malformed body", "400 <empty body>");
    expectedForXml.put("400 validation", "400 <empty body>");
    expectedForXml.put("404 unknown route", "404 <empty body>");
    expectedForXml.put("405 method not allowed", "405 <empty body>");
    expectedForXml.put("415 unsupported media type", "415 <empty body>");
    expectedForXml.put("422 domain guard", "422 <empty body>");
    assertThat(xmlCaller)
        .as("a caller who accepts none of our types keeps the status and loses the body")
        .containsExactlyInAnyOrderEntriesOf(expectedForXml);
    assertThat(htmlCaller)
        .as("and text/html is no more writable than XML, so it is treated the same way")
        .containsExactlyInAnyOrderEntriesOf(expectedForXml);

    // The status is the part that must not move between the two, and it is asserted as such
    // rather than left to be read off the two maps above.
    jsonCaller.forEach(
        (label, shape) ->
            assertThat(xmlCaller.get(label).substring(0, 3))
                .as("%s answered a different status depending on the Accept header", label)
                .isEqualTo(shape.substring(0, 3)));

    // And the 406 itself, which exists only for the caller who accepts nothing we produce.
    assertThat(shapeOf(echo(MediaType.APPLICATION_XML))).isEqualTo("406 <empty body>");
    assertThat(shapeOf(echo(MediaType.APPLICATION_JSON))).startsWith("200 ");

    for (Map<String, String> census :
        List.of(jsonCaller, problemJsonCaller, xmlCaller, htmlCaller)) {
      census.forEach(
          (label, shape) ->
              assertThat(shape).as("%s must not be a server fault", label).doesNotStartWith("5"));
    }
  }

  /** The same six situations, driven with one {@code Accept} header, reduced to their contract. */
  private Map<String, String> census(MediaType accept) throws Exception {
    Map<String, String> census = new LinkedHashMap<>();
    census.put(
        "400 malformed body",
        shapeOf(
            mvc.perform(
                    post("/api/projects")
                        .with(TestIdentity.as(caller))
                        .with(csrf())
                        .accept(accept)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andReturn()));
    census.put(
        "400 validation",
        shapeOf(
            mvc.perform(
                    post("/api/auth/register")
                        .with(csrf())
                        .accept(accept)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"email\":\"not-an-email\",\"password\":\"x\",\"displayName\":\"\"}"))
                .andReturn()));
    census.put(
        "404 unknown route",
        shapeOf(
            mvc.perform(
                    get("/api/logging-probe/does-not-exist")
                        .with(TestIdentity.as(caller))
                        .accept(accept))
                .andReturn()));
    census.put(
        "405 method not allowed",
        shapeOf(
            mvc.perform(
                    get("/api/logging-probe/echo").with(TestIdentity.as(caller)).accept(accept))
                .andReturn()));
    census.put(
        "415 unsupported media type",
        shapeOf(
            mvc.perform(
                    post("/api/logging-probe/echo")
                        .with(TestIdentity.as(caller))
                        .with(csrf())
                        .accept(accept)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain"))
                .andReturn()));
    census.put(
        "422 domain guard",
        shapeOf(
            mvc.perform(
                    post("/api/logging-probe/illegal")
                        .with(TestIdentity.as(caller))
                        .with(csrf())
                        .accept(accept)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andReturn()));
    return census;
  }

  /**
   * The finding the first version of this change left open: three of the four paths to a response
   * still wrote the 190-frame WARN, and two of them lost the response entirely.
   *
   * <p>Fixing only the dispatched {@code HttpMediaTypeNotAcceptableException} fixed the path where
   * the controller had succeeded. It did nothing for a request that was already failing when the
   * header was applied, because Spring does not re-dispatch an exception thrown while writing an
   * {@code @ExceptionHandler}'s return value — it logs at WARN with the throwable and gives up.
   * So the same header, one route further along, produced the same trace and additionally threw the
   * mapped status away.
   *
   * <p>Measured on all four paths, before and after, and the numbers are in the commit message. The
   * assertion here is the property rather than the numbers: whatever the caller accepts, an
   * expected error writes no frames, keeps its own status, and does not escape the dispatcher.
   */
  @Test
  @DisplayName("Every path to a response survives an Accept header it cannot satisfy")
  void noPathToAResponseIsLostToTheAcceptHeader() {
    record Path(String label, int status, MvcResult result) {}

    List<Path> paths = new ArrayList<>();
    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> {
              paths.add(new Path("controller succeeds", 406, echo(MediaType.APPLICATION_XML)));
              paths.add(
                  new Path(
                      "mapped 422",
                      422,
                      perform(
                          post("/api/logging-probe/illegal"), MediaType.APPLICATION_XML)));
              paths.add(
                  new Path(
                      "unknown route 404",
                      404,
                      perform(
                          get("/api/logging-probe/does-not-exist"), MediaType.APPLICATION_XML)));
            });

    for (Path path : paths) {
      assertThat(path.result().getResponse().getStatus())
          .as("%s lost its status to the Accept header", path.label())
          .isEqualTo(path.status());
    }
    // Reaching this line at all is half the assertion: before the fix, two of these paths threw a
    // ServletException out of mvc.perform rather than returning a result to inspect.
    assertThat(framesAtOrAbove(events, Level.WARN))
        .as("no expected error may write a stack frame, whatever the caller accepts")
        .isEqualTo(0);

    // The fourth path is the genuine 500, and it is the one that must still be loud. Captured
    // separately only so the frame counts above stay about expected errors.
    List<ILoggingEvent> serverFault =
        LogCapture.capturing(
            () ->
                assertThat(
                        perform(post("/api/logging-probe/boom"), MediaType.APPLICATION_XML)
                            .getResponse()
                            .getStatus())
                    .as("a real 500 must still be a 500 on the wire, not a lost response")
                    .isEqualTo(500));
    assertThat(framesAtOrAbove(serverFault, Level.ERROR))
        .as("an unacceptable Accept header must not cost a server fault its stack trace")
        .isGreaterThan(20);
    assertThat(framesAtOrAbove(serverFault, Level.WARN) - framesAtOrAbove(serverFault, Level.ERROR))
        .as("and it must not add the resolver's WARN trace back on top")
        .isEqualTo(0);
  }

  /**
   * The other half of the review's finding: one exception type, two opposite situations.
   *
   * <p>{@code HttpMediaTypeNotAcceptableException} is also thrown when no converter can write a
   * controller's return value, for a caller who accepts JSON and would have read it happily. That
   * is a fault in this application. The first version of this change caught it with the same branch
   * as the caller's mistake, so it lost its body and was tallied at DEBUG as an expected client
   * error — the closest thing in the whole change to filing a server fault as somebody else's
   * problem.
   */
  @Test
  @DisplayName("An unwritable return value is a server fault, not the caller's mistake")
  void anUnwritableReturnValueIsAServerFault() throws Exception {
    long countedBefore = expectedErrors.countOf(406, NOT_ACCEPTABLE_KIND);

    MvcResult[] result = new MvcResult[1];
    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> result[0] = perform(post("/api/logging-probe/unwritable"), MediaType.APPLICATION_JSON));

    // The body this caller received before this task existed, restored exactly.
    assertThat(result[0].getResponse().getStatus()).isEqualTo(406);
    com.fasterxml.jackson.databind.JsonNode returned =
        json.readTree(result[0].getResponse().getContentAsString());
    assertThat(returned.get("status").asInt()).isEqualTo(406);
    assertThat(returned.get("code").asText()).isEqualTo("BAD_REQUEST");
    assertThat(returned.get("message").asText()).isEqualTo("Not Acceptable");

    // It is traced, because it is ours.
    assertThat(framesAtOrAbove(events, Level.ERROR))
        .as("a fault in this application must not be quiet just because it wears a 406")
        .isGreaterThan(20);
    // And it is not in the expected-client-error tally.
    assertThat(expectedErrors.countOf(406, NOT_ACCEPTABLE_KIND) - countedBefore)
        .as("a server fault counted as an expected client error is the failure mode to avoid")
        .isZero();
  }

  /**
   * The key space is capped rather than argued to be small.
   *
   * <p>The javadoc used to claim the map could not grow without bound because the vocabulary is
   * fixed. That was a statement about today's callers of this class, not a property of the class:
   * any integer is a status and any {@code ErrorResponse} may carry one. So it is enforced, and
   * this is what enforces it.
   */
  @Test
  @DisplayName("The tally cannot be grown without bound, and degrades to a bucket not to silence")
  void theKeySpaceIsCapped() {
    ExpectedHttpErrorLog tally = new ExpectedHttpErrorLog();
    for (int status = 400; status < 900; status++) {
      tally.record(status, "Kind" + status);
    }

    assertThat(tally.distinctKinds())
        .as("five hundred distinct kinds must not become five hundred map entries")
        .isEqualTo(ExpectedHttpErrorLog.MAX_KINDS);
    assertThat(tally.countOf(400, "Kind400"))
        .as("the kinds seen first keep a bucket of their own")
        .isEqualTo(1);
    assertThat(tally.countOf(899, "Kind899"))
        .as("a kind invented after the cap gets no bucket of its own")
        .isZero();
    assertThat(tally.overflowCount())
        .as("but it is still counted: the tally degrades to one bucket rather than to silence")
        .isEqualTo(500 - (ExpectedHttpErrorLog.MAX_KINDS - 1));
  }

  @Test
  @DisplayName("Every expected client error is counted, and the unexpected one is not")
  void theCountedPopulationIsExactlyTheExpectedOne() throws Exception {
    long notFoundBefore = expectedErrors.countOf(404, "NoResourceFoundException");
    long unsupportedBefore = expectedErrors.countOf(415, "HttpMediaTypeNotSupportedException");

    mvc.perform(get("/api/logging-probe/does-not-exist").with(TestIdentity.as(caller))).andReturn();
    mvc.perform(
            post("/api/logging-probe/echo")
                .with(TestIdentity.as(caller))
                .with(csrf())
                .contentType(MediaType.TEXT_PLAIN)
                .content("plain"))
        .andReturn();

    assertThat(expectedErrors.countOf(404, "NoResourceFoundException") - notFoundBefore)
        .as("an unknown route is an expected client error and is counted rather than silent")
        .isEqualTo(1);
    assertThat(expectedErrors.countOf(415, "HttpMediaTypeNotSupportedException") - unsupportedBefore)
        .as("a body in an unsupported media type is counted too")
        .isEqualTo(1);

    // And the boundary: a server fault is not in this population, at any status and by any key.
    // The second assertion is review's R2-B: respond() drops the body of a 5xx too when the caller
    // accepts nothing, and recording that as an expected client error would have written the line
    // "Expected client error 500 BodyOmittedForAcceptHeader" — the same mislabelling as F2, one
    // level down, and it would have made this test's own name false.
    boom();
    perform(post("/api/logging-probe/boom"), MediaType.APPLICATION_XML);
    assertThat(expectedErrors.countOf(500, "UnsupportedOperationException")).isZero();
    assertThat(expectedErrors.countOf(500, "BodyOmittedForAcceptHeader"))
        .as("a 500 whose body was dropped is still a server fault, not an expected client error")
        .isZero();
  }

  @Test
  @DisplayName("The announcement schedule is logarithmic, so WARN volume is bounded")
  void theAnnouncementScheduleIsBounded() {
    assertThat(ExpectedHttpErrorLog.isAnnounced(0)).isFalse();
    assertThat(ExpectedHttpErrorLog.isAnnounced(1)).isTrue();
    assertThat(ExpectedHttpErrorLog.isAnnounced(2)).isFalse();
    assertThat(ExpectedHttpErrorLog.isAnnounced(9)).isFalse();
    assertThat(ExpectedHttpErrorLog.isAnnounced(10)).isTrue();
    assertThat(ExpectedHttpErrorLog.isAnnounced(11)).isFalse();
    assertThat(ExpectedHttpErrorLog.isAnnounced(100)).isTrue();
    assertThat(ExpectedHttpErrorLog.isAnnounced(1000)).isTrue();
    assertThat(ExpectedHttpErrorLog.isAnnounced(1001)).isFalse();

    long announced = 0;
    for (long n = 1; n <= 1_000_000; n++) {
      if (ExpectedHttpErrorLog.isAnnounced(n)) {
        announced++;
      }
    }
    assertThat(announced)
        .as("a million expected client errors must not be a million WARN lines")
        .isEqualTo(7);
  }

  // ---------------------------------------------------------------- helpers

  private String body() {
    return "{\"value\":\"" + FIXTURE + "\"}";
  }

  private MvcResult echo(MediaType accept) {
    try {
      return mvc.perform(
              post("/api/logging-probe/echo")
                  .with(TestIdentity.as(caller))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(accept)
                  .content(body()))
          .andReturn();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private MvcResult perform(MockHttpServletRequestBuilder builder, MediaType accept) {
    try {
      return mvc.perform(
              builder
                  .with(TestIdentity.as(caller))
                  .with(csrf())
                  .accept(accept)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body()))
          .andReturn();
    } catch (Exception e) {
      // Before the fix two of these paths threw a ServletException out of the dispatcher rather
      // than returning a response, so this is not dead code: it is the failure being prevented,
      // and it is reported as such rather than as an opaque wrapped throwable.
      throw new AssertionError("the request escaped the dispatcher instead of answering", e);
    }
  }

  private MvcResult boom() {
    try {
      return mvc.perform(
              post("/api/logging-probe/boom")
                  .with(TestIdentity.as(caller))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body()))
          .andReturn();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** One response reduced to what its contract promises: the status and the error code. */
  private String shapeOf(MvcResult result) throws Exception {
    int status = result.getResponse().getStatus();
    String content = result.getResponse().getContentAsString();
    if (content.isBlank()) {
      return status + " <empty body>";
    }
    com.fasterxml.jackson.databind.JsonNode code = json.readTree(content).get("code");
    // A success carries no error code, and saying so is better than a NullPointerException in a
    // helper: this method is used to compare a 200 against the error shapes too.
    return status + " " + (code == null ? "<no code>" : code.asText());
  }

  /** Every stack frame attached to an event at or above {@code threshold}, causes included. */
  private static int framesAtOrAbove(List<ILoggingEvent> events, Level threshold) {
    int frames = 0;
    for (ILoggingEvent event : events) {
      if (event.getLevel().isGreaterOrEqual(threshold)) {
        frames +=
            framesOf(event.getThrowableProxy(), Collections.newSetFromMap(new IdentityHashMap<>()));
      }
    }
    return frames;
  }

  private static int framesOf(IThrowableProxy throwable, Set<IThrowableProxy> seen) {
    if (throwable == null || !seen.add(throwable)) {
      return 0;
    }
    int frames =
        throwable.getStackTraceElementProxyArray() == null
            ? 0
            : throwable.getStackTraceElementProxyArray().length;
    frames += framesOf(throwable.getCause(), seen);
    IThrowableProxy[] suppressed = throwable.getSuppressed();
    if (suppressed != null) {
      for (IThrowableProxy each : suppressed) {
        frames += framesOf(each, seen);
      }
    }
    return frames;
  }

  /** WARN-and-above events carrying a throwable: what an operator actually reads as a trace. */
  private static List<String> operatorVisibleTraces(List<ILoggingEvent> events) {
    List<String> traces = new ArrayList<>();
    for (ILoggingEvent event : events) {
      if (event.getLevel().isGreaterOrEqual(Level.WARN) && event.getThrowableProxy() != null) {
        traces.add(
            event.getLoggerName() + " @" + event.getLevel() + ": " + LogCapture.lineOf(event));
      }
    }
    return traces;
  }

  private static List<String> linesFrom(List<ILoggingEvent> events, String logger, Level level) {
    List<String> lines = new ArrayList<>();
    for (ILoggingEvent event : events) {
      if (event.getLoggerName().equals(logger) && event.getLevel().equals(level)) {
        lines.add(LogCapture.lineOf(event));
      }
    }
    return lines;
  }

  // ---------------------------------------------------------------- the probe route

  record Echo(String value) {}

  /**
   * A route that fails in the two ways the application itself never does on purpose.
   *
   * <p>{@code /boom} throws an exception no handler claims, so it lands in the last-resort branch
   * as a real 500. {@code /illegal} throws the guard exception the domain uses, so the 422 mapping
   * is exercised without depending on any one module's rules. {@code /echo} returns what it was
   * given, so the same route can be driven to 200, 406 and 415 with the caller's text in the body
   * every time.
   */
  @RestController
  @RequestMapping("/api/logging-probe")
  static class LoggingProbeController {

    @PostMapping("/echo")
    Echo echo(@RequestBody Echo request) {
      return request;
    }

    @PostMapping("/boom")
    Echo boom(@RequestBody Echo request) {
      throw new UnsupportedOperationException("probe: an internal failure nobody mapped");
    }

    @PostMapping("/illegal")
    Echo illegal(@RequestBody Echo request) {
      throw new IllegalStateException("probe: a guard inside the domain");
    }

    /**
     * A return value no message converter claims, for a caller who accepts JSON perfectly well.
     *
     * <p>This is what a fault in this application looks like from the outside: the caller did
     * everything right and we still cannot produce a representation.
     */
    @PostMapping("/unwritable")
    Object unwritable(@RequestBody Echo request) {
      return new java.io.ByteArrayInputStream(new byte[0]) {};
    }
  }

  @TestConfiguration
  static class ProbeRoutes {
    @Bean
    LoggingProbeController loggingProbeController() {
      return new LoggingProbeController();
    }
  }
}
