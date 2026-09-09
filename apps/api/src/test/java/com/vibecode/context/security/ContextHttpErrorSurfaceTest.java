package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.domain.User;
import com.vibecode.shared.logging.LogCapture;
import com.vibecode.support.TestIdentity;
import com.vibecode.support.logging.LoggerLevelIsolation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The Context API's error surface, attacked with the inputs a client sends by accident and an
 * attacker sends on purpose.
 *
 * <p>Three properties are pinned, and they are different claims that a single test would blur.
 *
 * <ol>
 *   <li><b>Nothing here is a 500.</b> A malformed request parameter, a method the route does not
 *       serve, a body in the wrong media type and a body that is far too large are all client
 *       mistakes. Any of them arriving as a server fault is both a wrong answer to the caller and a
 *       stack trace in the log, which is where an attacker reads the class names.
 *   <li><b>No error body carries internal detail.</b> No package name, no SQL, no driver text, no
 *       frame.
 *   <li><b>The {@code limit} parameter's error contract is enumerated, not sampled.</b> It is
 *       already known to answer in two different shapes — {@code BAD_REQUEST} with an empty
 *       {@code violations} array when the number is out of range, {@code VALIDATION_ERROR} with a
 *       populated one when it is not a number — because {@code HandlerMethodValidationException}
 *       has no handler. Two shapes are recorded debt. This class walks fifteen spellings of that one
 *       parameter and pins the whole census, and finds a <b>third</b>: two of the fifteen are not
 *       refused at all but served with a page size the caller never named. That is FINDING
 *       CTX-09B-2, written up on the test that measures it.
 * </ol>
 *
 * <p>The census is asserted as an exact set rather than as "at most two shapes". An
 * {@code isLessThanOrEqualTo} would keep passing if a shape disappeared, and a shape disappearing
 * is how a 400 quietly becomes a 200 that returns more rows than the caller asked for.
 */
@AutoConfigureMockMvc
@ExtendWith(LoggerLevelIsolation.class)
class ContextHttpErrorSurfaceTest extends ContextProbeFixture {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private User alice;
  private User bob;
  private UUID aliceProject;
  private UUID bobProject;
  private UUID bobPack;
  private Logger root;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void twoUsersEachWithAProject() throws Exception {
    alice = identity.createAndAuthenticate("surface-alice");
    aliceProject = projects.create("Alice surface", "A project", "Ship a tool").getId();

    bob = identity.createAndAuthenticate("surface-bob");
    bobProject = projects.create("Bob surface", "A project", "Ship a tool").getId();
    bobPack =
        UUID.fromString(
            json.readTree(
                    mvc.perform(
                            post(url(bobProject) + "/compile")
                                .with(TestIdentity.as(bob))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"taskReference\":\"BOB-1\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("packId")
                .asText());

    identity.authenticateAs(alice);
    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    captured = new ListAppender<>();
    captured.start();
    root.addAppender(captured);
    root.setLevel(Level.TRACE);
  }

  @AfterEach
  void stopListening() {
    root.detachAppender(captured);
    captured.stop();
  }

  // ------------------------------------------------------------------ the limit census

  /**
   * Fifteen spellings of one integer parameter, and the exact contract each one produces.
   *
   * <p>The list hits different layers on purpose: a value the type converter refuses ({@code abc},
   * {@code 5.5}), a value it converts and the bean validator then refuses ({@code 0}, {@code 101}),
   * a value that overflows the target type, a repeated parameter that arrives as {@code "1,2"}, an
   * empty value where a {@code defaultValue} is declared, a hexadecimal literal, and a Unicode digit
   * that looks like a number to a human and is not one to {@code Integer.parseInt}.
   *
   * <p><b>FINDING CTX-09B-2: there is a third shape, and it is a 200.</b> The two known shapes are
   * both refusals. {@code ?limit=} and {@code ?limit=0x10} are not refused at all — the first is
   * served with the default page size, the second with sixteen. So one parameter answers in three
   * contracts: {@code 400 BAD_REQUEST} with an empty {@code violations} array when the number is out
   * of range, {@code 400 VALIDATION_ERROR} with a populated one when it cannot be parsed, and
   * {@code 200} with a page size the caller did not ask for when it is empty or hexadecimal.
   *
   * <p>The empty case is the one worth acting on, and not because 200 is the wrong status —
   * {@code defaultValue} standing in for an absent parameter is Spring behaving as documented, and a
   * client that writes {@code ?limit=} has arguably said nothing. It is worth acting on because this
   * route's own contract says the opposite in writing: out of range is <em>refused rather than
   * clamped</em>, on the stated grounds that "a caller who asked for a thousand and silently received
   * a hundred would have no way to know the answer had been narrowed". A caller whose variable
   * interpolated to empty is in exactly that position, and reads a page of twenty as the whole list.
   * That is the failure mode the route already refuses to create, arriving through a spelling nobody
   * enumerated.
   *
   * <p>The hexadecimal case is a curiosity rather than a hole, and is pinned so it stays one. It is
   * also the proof that the value is genuinely parsed and not discarded: {@code 0x10} is sixteen and
   * passes, {@code 0x65} is a hundred and one and is refused by {@code @Max}. Were hex simply
   * dropped, both would be served.
   */
  @Test
  @DisplayName("FINDING: the limit parameter answers in three contracts, and one of them is a 200")
  void theLimitParameterHasAThirdErrorShape() throws Exception {
    List<String> spellings =
        List.of(
            "0",
            "-1",
            "101",
            "2147483648",
            "-2147483649",
            "abc",
            "",
            " ",
            "5.5",
            "0x10",
            "+7",
            "1,2",
            "\uFF15",
            "1;DROP TABLE context_packs",
            "1 OR 1=1");

    int packsBefore = packCount();
    Map<String, String> census = new LinkedHashMap<>();
    for (String value : spellings) {
      MvcResult result =
          mvc.perform(
                  get(url(aliceProject) + "?limit=" + encode(value)).with(TestIdentity.as(alice)))
              .andReturn();
      census.put(value, shapeOf(result));
      assertBodyIsClean("limit=" + value, result);
    }

    // Property first. Whatever the contracts turn out to be, none may be a server fault: a client's
    // mis-spelled query parameter reported as a 500 is a stack trace in the log and a wrong answer.
    census.forEach(
        (value, shape) ->
            assertThat(shape)
                .as("limit=%s must be a client error or a served page, never a server fault", value)
                .doesNotStartWith("5"));

    // The census, pinned entry by entry rather than as a count of distinct shapes. A count would
    // stay green if two inputs swapped contracts with each other, and swapping a refusal for a 200
    // is precisely the change this test exists to catch.
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("0", "400 BAD_REQUEST violations=0");
    expected.put("-1", "400 BAD_REQUEST violations=0");
    expected.put("101", "400 BAD_REQUEST violations=0");
    expected.put("2147483648", "400 VALIDATION_ERROR violations=1");
    expected.put("-2147483649", "400 VALIDATION_ERROR violations=1");
    expected.put("abc", "400 VALIDATION_ERROR violations=1");
    expected.put("", "200 <no code> violations=<absent>");
    expected.put(" ", "400 VALIDATION_ERROR violations=1");
    expected.put("5.5", "400 VALIDATION_ERROR violations=1");
    expected.put("0x10", "200 <no code> violations=<absent>");
    expected.put("+7", "400 VALIDATION_ERROR violations=1");
    expected.put("1,2", "400 VALIDATION_ERROR violations=1");
    expected.put("\uFF15", "400 VALIDATION_ERROR violations=1");
    expected.put("1;DROP TABLE context_packs", "400 VALIDATION_ERROR violations=1");
    expected.put("1 OR 1=1", "400 VALIDATION_ERROR violations=1");
    assertThat(census)
        .as("the contracts one query parameter answers in")
        .containsExactlyInAnyOrderEntriesOf(expected);

    assertThat(census.values().stream().distinct().toList())
        .as("FINDING CTX-09B-2: three contracts for one parameter, one of which serves a page")
        .hasSize(3);

    // What the 200 actually served: the default page, for a caller who named no number. Asserting
    // the status alone would leave "200" ambiguous between "defaulted" and "unbounded".
    String defaulted =
        mvc.perform(get(url(aliceProject) + "?limit=").with(TestIdentity.as(alice)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String omitted =
        mvc.perform(get(url(aliceProject)).with(TestIdentity.as(alice)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(defaulted)
        .as("an empty limit is served as though the caller had named none")
        .isEqualTo(omitted);

    // And the proof that a hexadecimal limit is parsed rather than discarded: the same notation out
    // of range is refused, which could not happen if the value were being ignored.
    mvc.perform(get(url(aliceProject) + "?limit=0x65").with(TestIdentity.as(alice)))
        .andExpect(status().isBadRequest());

    // The route still works and the table is still there. The injection spellings above are the
    // ones that would have removed it.
    mvc.perform(get(url(aliceProject)).with(TestIdentity.as(alice))).andExpect(status().isOk());
    assertThat(packCount())
        .as("the parameter was bound, not concatenated into a statement")
        .isEqualTo(packsBefore);
  }

  /**
   * The accepted end of the range, so the refusals above are known to be about the values and not
   * about a route that refuses everything.
   */
  @Test
  @DisplayName("The accepted end of the range really is accepted, so the refusals mean something")
  void theAcceptedLimitsAreAccepted() throws Exception {
    for (String value : List.of("1", "20", "100")) {
      mvc.perform(get(url(aliceProject) + "?limit=" + encode(value)).with(TestIdentity.as(alice)))
          .andExpect(status().isOk());
    }
  }

  // ------------------------------------------------------------------ the method and media surface

  /**
   * Methods and media types the routes do not serve.
   *
   * <p>The compile route is the interesting one: it is a {@code POST} that writes, and every other
   * verb aimed at it must be refused without writing. The two GET routes are checked for the same
   * thing from the other direction — a {@code PUT} or {@code DELETE} that happened to reach a
   * handler would be a mutation on a read path.
   */
  @Test
  @DisplayName("No unserved method or media type reaches a handler, and none of them is a 500")
  void theUnservedSurfaceIsAControlledClientError() throws Exception {
    UUID packId = compileForAlice("ALICE-METHOD");
    int before = packCount();

    List<MvcResult> results = new ArrayList<>();
    results.add(
        mvc.perform(get(url(aliceProject) + "/compile").with(TestIdentity.as(alice))).andReturn());
    results.add(
        mvc.perform(put(url(aliceProject) + "/" + packId).with(TestIdentity.as(alice)).with(csrf()))
            .andReturn());
    results.add(
        mvc.perform(
                delete(url(aliceProject) + "/" + packId).with(TestIdentity.as(alice)).with(csrf()))
            .andReturn());
    results.add(
        mvc.perform(
                patch(url(aliceProject) + "/" + packId).with(TestIdentity.as(alice)).with(csrf()))
            .andReturn());
    results.add(
        mvc.perform(post(url(aliceProject)).with(TestIdentity.as(alice)).with(csrf())).andReturn());
    results.add(
        mvc.perform(
                post(url(aliceProject) + "/compile")
                    .with(TestIdentity.as(alice))
                    .with(csrf())
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("{\"taskReference\":\"ALICE-TEXT\"}"))
            .andReturn());
    results.add(
        mvc.perform(
                post(url(aliceProject) + "/compile")
                    .with(TestIdentity.as(alice))
                    .with(csrf())
                    .content("{\"taskReference\":\"ALICE-NO-TYPE\"}"))
            .andReturn());
    results.add(
        mvc.perform(
                get(url(aliceProject)).with(TestIdentity.as(alice)).accept(MediaType.APPLICATION_XML))
            .andReturn());

    for (MvcResult result : results) {
      int statusCode = result.getResponse().getStatus();
      assertThat(statusCode)
          .as("%s %s answered %s", method(result), uri(result), statusCode)
          .isBetween(400, 499);
      assertBodyIsClean(method(result) + " " + uri(result), result);
    }

    assertThat(packCount())
        .as("no unserved verb or media type compiled anything")
        .isEqualTo(before);
    // And the pack that did exist was not modified or removed by the PUT/DELETE/PATCH above.
    mvc.perform(get(url(aliceProject) + "/" + packId).with(TestIdentity.as(alice)))
        .andExpect(status().isOk());
  }

  /**
   * <b>FINDING CTX-09B-3: one header a client fully controls writes a stack trace to the log on
   * every request.</b>
   *
   * <p>{@code Accept: application/xml} on any of the three routes produces a 406, which is the right
   * status. Getting there costs a WARN from {@code ExceptionHandlerExceptionResolver} carrying a
   * fifty-frame stack trace, because the shared handler resolves the exception into an
   * {@code ApiError} that it then cannot serialise into a media type the caller will accept — the
   * error handler itself fails, and Spring reports that failure at WARN.
   *
   * <p>Two things are separated here, because they are different claims and one is much worse than
   * the other. It is <b>not a leak</b>: the trace says "No acceptable representation" and carries no
   * request content, no caller text and no secret, and that is asserted rather than assumed. It
   * <b>is</b> unbounded log noise under the caller's control — the same request repeated writes the
   * trace again each time, and the handler's own javadoc gives filling "the log with noise that
   * hides real failures" as the reason it maps client mistakes deliberately. This one is not mapped,
   * because it is not a mapping problem: {@code HttpMediaTypeNotAcceptableException} is raised while
   * writing the response, after the handler has already run.
   *
   * <p>Owned by {@code shared/web} rather than by the Context API — every JSON route in the
   * application has it — but it is measured here because these routes are where this task was asked
   * to look, and nothing else in the suite had noticed it.
   */
  @Test
  @DisplayName("FINDING: an unacceptable Accept header is a 406 that costs a stack trace in the log")
  void anUnacceptableAcceptHeaderIsLoggedAsAStackTrace() throws Exception {
    UUID packId = compileForAlice("ALICE-ACCEPT");
    int linesBefore = captured.list.size();

    for (String path : List.of(url(aliceProject), url(aliceProject) + "/" + packId)) {
      mvc.perform(
              get(path).with(TestIdentity.as(alice)).accept(MediaType.APPLICATION_XML))
          .andExpect(status().isNotAcceptable());
    }

    // WARN and above, and carrying an actual throwable. Each of these two requests produces two
    // WARN lines naming the exception — the resolver's "Resolved [...]" summary, which is text
    // only, and "Failure in @ExceptionHandler", which attaches the throwable and is therefore the
    // one that renders a stack trace into the log file. Counting only the second makes the number
    // mean "stack traces an operator gets", which is the claim, rather than "mentions".
    List<String> traces =
        captured.list.stream()
            .skip(linesBefore)
            .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
            .filter(event -> event.getThrowableProxy() != null)
            .filter(event -> LogCapture.lineOf(event).contains("HttpMediaTypeNotAcceptableException"))
            .map(LogCapture::lineOf)
            .toList();

    assertThat(traces)
        .as(
            "FINDING CTX-09B-3: a header the caller chooses puts a WARN and a stack trace in the log"
                + " on every request; two requests, two traces, at a level an operator sees")
        .hasSize(2);

    // The half that matters more, and it is the good half: the trace carries nothing of the caller
    // or of the project. Read through the chain-walking renderer, so a value hiding two causes down
    // would be found rather than missed.
    for (String trace : traces) {
      assertThat(trace)
          .as("the trace must not describe the project or repeat caller text")
          .doesNotContain("Alice surface")
          .doesNotContain("ALICE-ACCEPT")
          .doesNotContain(aliceProject.toString());
    }

    // The 406 body itself is not a place internal detail escaped to either.
    MvcResult result =
        mvc.perform(get(url(aliceProject)).with(TestIdentity.as(alice)).accept(MediaType.APPLICATION_XML))
            .andReturn();
    assertBodyIsClean("406 body", result);
  }

  // ------------------------------------------------------------------ size

  /**
   * A body far larger than anything the route could accept.
   *
   * <p>Two things are being asked. Is it refused as a client error rather than blowing up? And —
   * the part that matters more — is the oversized value kept out of the response and out of the
   * log? A validation failure that reports the offending value is how a megabyte of caller text,
   * secrets included, ends up in an error body and a log file at once.
   */
  @Test
  @DisplayName("A megabyte task reference is refused without echoing it and without storing a pack")
  void anOversizedBodyIsRefusedCleanly() throws Exception {
    int before = packCount();
    String needle = "oversize_zqxw_884120";
    String huge = needle + "A".repeat(1_000_000);

    MvcResult result =
        mvc.perform(
                post(url(aliceProject) + "/compile")
                    .with(TestIdentity.as(alice))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"taskReference\":\"" + huge + "\"}"))
            .andReturn();

    assertThat(result.getResponse().getStatus())
        .as("a body this size is the caller's mistake, not the server's")
        .isBetween(400, 499);
    assertBodyIsClean("oversized body", result);
    assertThat(result.getResponse().getContentAsString())
        .as("the rejected value must not be echoed; at this size it would also be a response bomb")
        .doesNotContain(needle);
    assertThat(result.getResponse().getContentLength() < 10_000
            || result.getResponse().getContentAsString().length() < 10_000)
        .as("the error body is a sentence, not a copy of the request")
        .isTrue();
    assertThat(logLinesContaining(needle)).as("the oversized value reached a log line").isEmpty();
    assertThat(packCount()).isEqualTo(before);
  }

  // ------------------------------------------------------------------ cross-user identity

  /**
   * A real pack id belonging to another user, offered under the caller's own project.
   *
   * <p>Distinct from the case the API's own tests cover, which pairs a real pack with a second
   * project the same caller owns. Here the id is genuinely someone else's, so a route that scoped
   * only by pack id — or that reported "forbidden" for a pack it could see but not serve — would
   * confirm to the caller that the id names a real pack in someone else's project. The answer must
   * be indistinguishable from the answer for an id that never existed, body included.
   */
  @Test
  @DisplayName("Another user's real pack id is not found, and reads identically to a made-up one")
  void anotherUsersPackIdIsIndistinguishableFromNothing() throws Exception {
    UUID invented = UUID.randomUUID();

    MvcResult real =
        mvc.perform(get(url(aliceProject) + "/" + bobPack).with(TestIdentity.as(alice))).andReturn();
    MvcResult fake =
        mvc.perform(get(url(aliceProject) + "/" + invented).with(TestIdentity.as(alice)))
            .andReturn();

    assertThat(real.getResponse().getStatus())
        .as("a 403 here would confirm the id names a real pack")
        .isEqualTo(404);
    assertThat(fake.getResponse().getStatus()).isEqualTo(404);

    // The bodies differ only where they name the id the caller themselves supplied. Replacing each
    // id with a fixed token makes the two comparable; if anything else differed, the route would be
    // an oracle for "this id exists somewhere".
    assertThat(normalise(real.getResponse().getContentAsString(), bobPack))
        .as("the response distinguishes a real pack from an imaginary one")
        .isEqualTo(normalise(fake.getResponse().getContentAsString(), invented));

    // Bob's pack is untouched and still his.
    mvc.perform(get(url(bobProject) + "/" + bobPack).with(TestIdentity.as(bob)))
        .andExpect(status().isOk());
    assertBodyIsClean("cross-user 404", real);
  }

  // ------------------------------------------------------------------ negative control

  /**
   * The in-file control. The out-of-file one was run and is recorded on
   * {@code ContextHttpSecretExposureTest#anUnmappedPathServesNothing}: with the {@code url(...)}
   * helper here pointed at an unmapped path, all seven tests in this class failed — the six below
   * and this one, which fails through its own {@code @BeforeEach}, because the fixture compiles a
   * pack over HTTP and that request 404s.
   */
  @Test
  @DisplayName("Negative control: an unmapped sibling path serves none of these routes")
  void anUnmappedSiblingServesNothing() throws Exception {
    String dead = "/api/projects/" + aliceProject + "/context-surface-not-a-route";
    mvc.perform(get(dead).with(TestIdentity.as(alice))).andExpect(status().isNotFound());
    mvc.perform(get(dead + "?limit=0").with(TestIdentity.as(alice))).andExpect(status().isNotFound());
    mvc.perform(
            post(dead + "/compile")
                .with(TestIdentity.as(alice))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskReference\":\"DEAD\"}"))
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------ helpers

  private static String url(UUID project) {
    return "/api/projects/" + project + "/context";
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private UUID compileForAlice(String taskReference) throws Exception {
    String response =
        mvc.perform(
                post(url(aliceProject) + "/compile")
                    .with(TestIdentity.as(alice))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"taskReference\":\"" + taskReference + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("packId").asText());
  }

  /** One response reduced to its error contract: status, code, and how many violations it named. */
  private String shapeOf(MvcResult result) throws Exception {
    int statusCode = result.getResponse().getStatus();
    String content = result.getResponse().getContentAsString();
    if (content.isBlank()) {
      return statusCode + " <empty body>";
    }
    JsonNode body = json.readTree(content);
    JsonNode code = body.get("code");
    JsonNode violations = body.get("violations");
    return statusCode
        + " "
        + (code == null ? "<no code>" : code.asText())
        + " violations="
        + (violations == null || violations.isNull() ? "<absent>" : violations.size());
  }

  private void assertBodyIsClean(String label, MvcResult result) throws Exception {
    String content = result.getResponse().getContentAsString();
    assertThat(content)
        .as("%s leaked an internal detail", label)
        .doesNotContain("org.hibernate")
        .doesNotContain("org.springframework")
        .doesNotContain("com.vibecode")
        .doesNotContain("java.lang")
        .doesNotContain("jakarta.")
        .doesNotContain("SELECT ")
        .doesNotContain("Exception")
        .doesNotContain("\tat ");
  }

  /**
   * One error body with the two things that legitimately differ between any two responses removed:
   * the id the caller themselves supplied, and the instant the answer was produced. Everything that
   * survives this is something the route chose to say, and the two bodies must agree on all of it.
   */
  private static String normalise(String body, UUID suppliedId) {
    return body
        .replace(suppliedId.toString(), "<id>")
        .replaceAll("\"timestamp\":\"[^\"]+\"", "\"timestamp\":\"<t>\"");
  }

  private int packCount() {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM context_packs", Integer.class);
    return count == null ? 0 : count;
  }

  private static String method(MvcResult result) {
    return result.getRequest().getMethod();
  }

  private static String uri(MvcResult result) {
    return result.getRequest().getRequestURI();
  }

  /** Captured lines carrying the needle, read through the chain-walking renderer. */
  private List<String> logLinesContaining(String needle) {
    List<String> hits = new ArrayList<>();
    for (ILoggingEvent event : List.copyOf(captured.list)) {
      String line = LogCapture.lineOf(event);
      if (line.contains(needle)) {
        hits.add(event.getLoggerName() + " @" + event.getLevel() + ": " + line);
      }
    }
    return hits;
  }
}
