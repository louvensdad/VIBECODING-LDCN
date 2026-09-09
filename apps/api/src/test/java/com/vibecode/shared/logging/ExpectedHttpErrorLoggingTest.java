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

  @Test
  @DisplayName("400, 404, 406, 415 and 422 keep their status and their body")
  void theClientErrorContractIsUnchanged() throws Exception {
    Map<String, String> census = new LinkedHashMap<>();

    MvcResult malformed =
        mvc.perform(
                post("/api/projects")
                    .with(TestIdentity.as(caller))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ not json"))
            .andReturn();
    census.put("400 malformed body", shapeOf(malformed));

    MvcResult validation =
        mvc.perform(
                post("/api/auth/register")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"not-an-email\",\"password\":\"x\",\"displayName\":\"\"}"))
            .andReturn();
    census.put("400 validation", shapeOf(validation));

    MvcResult notFound =
        mvc.perform(get("/api/logging-probe/does-not-exist").with(TestIdentity.as(caller)))
            .andReturn();
    census.put("404 unknown route", shapeOf(notFound));

    census.put("406 unacceptable accept", shapeOf(echo(MediaType.APPLICATION_XML)));

    MvcResult unsupported =
        mvc.perform(
                post("/api/logging-probe/echo")
                    .with(TestIdentity.as(caller))
                    .with(csrf())
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("plain"))
            .andReturn();
    census.put("415 unsupported media type", shapeOf(unsupported));

    MvcResult illegalState =
        mvc.perform(
                post("/api/logging-probe/illegal")
                    .with(TestIdentity.as(caller))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body()))
            .andReturn();
    census.put("422 domain guard", shapeOf(illegalState));

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("400 malformed body", "400 MALFORMED_REQUEST");
    expected.put("400 validation", "400 VALIDATION_ERROR");
    expected.put("404 unknown route", "404 BAD_REQUEST");
    // The 406 has no body, and had none before this change either: the ApiError the last-resort
    // handler used to build could not be serialised into a type the caller accepts, so it never
    // reached the wire. Answering with no body is the same response, arrived at without failing.
    expected.put("406 unacceptable accept", "406 <empty body>");
    expected.put("415 unsupported media type", "415 BAD_REQUEST");
    expected.put("422 domain guard", "422 INVALID_STATE");
    assertThat(census)
        .as("the error contract this task was told not to move")
        .containsExactlyInAnyOrderEntriesOf(expected);

    census.forEach(
        (label, shape) ->
            assertThat(shape).as("%s must not be a server fault", label).doesNotStartWith("5"));
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

    // And the boundary: a server fault is not in this population, at any status.
    boom();
    assertThat(expectedErrors.countOf(500, "UnsupportedOperationException")).isZero();
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
    return status + " " + json.readTree(content).get("code").asText();
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
  }

  @TestConfiguration
  static class ProbeRoutes {
    @Bean
    LoggingProbeController loggingProbeController() {
      return new LoggingProbeController();
    }
  }
}
