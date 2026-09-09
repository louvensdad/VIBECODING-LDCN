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
import com.vibecode.shared.logging.ExpectedHttpErrorLog;
import com.vibecode.shared.logging.LogCapture;
import com.vibecode.support.TestIdentity;
import com.vibecode.support.logging.LoggerLevelIsolation;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The Context API's error surface, attacked with the inputs a client sends by accident and an
 * attacker sends on purpose.
 *
 * <p>Five properties are pinned, and they are different claims that a single test would blur.
 *
 * <ol>
 *   <li><b>Nothing here is a 500.</b> A malformed request parameter, a method the route does not
 *       serve, a body in the wrong media type, a body that cannot be parsed and a body that is far
 *       too large are all client mistakes. Any of them arriving as a server fault is both a wrong
 *       answer to the caller and a stack trace in the log, which is where an attacker reads the
 *       class names.
 *   <li><b>No error body carries internal detail.</b> No package name, no SQL, no driver text, no
 *       frame — and, since review found the claim was being made over requests that never reached
 *       the handler which could break it, that is now asserted on the {@code MALFORMED_REQUEST}
 *       path as well: unparseable JSON, a {@code budget} of the wrong type, a {@code budget} field
 *       of the wrong type, and an absent body. See {@link #aMalformedBodyNamesNoTypeAndEchoesNoValue}
 *       for what each of those returns when the handler is allowed to speak.
 *   <li><b>The {@code limit} parameter answers in exactly one contract.</b> It used to answer in
 *       three, one of which was a 200 with a page size the caller never named — that was FINDING
 *       CTX-09B-2, and CTX-API-R2 closed it by taking the parameter as raw text and deciding in the
 *       controller. This class walks fifteen spellings and pins the whole census, which is now a
 *       single shape: {@code 400 VALIDATION_ERROR} with one violation naming {@code limit}.
 *   <li><b>An {@code Accept} header the caller chooses does not decide what this API answers.</b>
 *       It used to: an error body that could not be serialised took the response down with it, and
 *       the status the caller read was not the status the route produced. See
 *       {@link #anUnacceptableAcceptHeaderIsCountedNotTraced}.
 *   <li><b>And that is true of the {@code limit} refusal too, which is where it stopped being
 *       true.</b> Properties 3 and 4 were closed by two separate pieces of work, and the seam
 *       between them was a hole: {@code limit} got its single contract from an advice of its own,
 *       which built its body without the {@code Accept} check the shared handler had just been
 *       given, so {@code ?limit=0} with {@code Accept: application/xml} escaped the resolver and
 *       answered 500 on a real container. That was FINDING CTX-09B-3b, this class asserted it as an
 *       open defect for as long as it was one, and
 *       {@link #aRefusedLimitKeepsItsStatusUnderAnUnacceptableAccept} is the same attack now
 *       asserting the fix. The cross-product behind it is
 *       {@link #theLimitContractHoldsAcrossEveryAcceptHeader}: eight spellings of {@code limit}
 *       against four {@code Accept} headers, pinned as status <em>and</em> body, because a
 *       status-only matrix is what let property 3 be broken in three different ways at once.
 *
 *       <p>And it was still not closed by that. Review found the check was comparing media types
 *       with {@code isCompatibleWith} against a list derived from a parameterless {@code canWrite},
 *       both of which discard media-type <em>parameters</em> — so a caller naming a charset Jackson
 *       cannot encode was promised a body it could not be given, and escaped exactly as before.
 *       {@code application/json;charset=ISO-8859-1} was a 500. The finding landed on this file by
 *       name: {@link #theRefusalSurvivesEveryShapeOfAcceptHeader} declared that it attacked the
 *       header itself and carried one charset row, {@code UTF-8}, one of the five Jackson accepts.
 *       It could not have failed for the reason it existed. The charsets that break the encoder are
 *       now sent, and {@link #aHonouredLimitUnderAnUnwritableCharsetIsCountedNotTraced} measures
 *       the log flood that reached the <em>success</em> path through the same hole.
 * </ol>
 *
 * <p>The census is asserted as an exact map rather than as a count of distinct shapes. A count
 * would keep passing if two inputs swapped contracts with each other, and swapping a refusal for a
 * 200 is precisely the change this test exists to catch.
 *
 * <h2>Two ways this file has manufactured a green before, both fixed here</h2>
 *
 * <p><b>The census measured {@code URLEncoder}, not the route.</b> Every row was built as
 * {@code get(url + "?limit=" + URLEncoder.encode(value))}, and {@code MockMvcRequestBuilders.get(String)}
 * runs {@code .encode()} over the template it is handed. An already-encoded value went through the
 * encoder twice, so the route received the literal text {@code "%2B7"} where the row claimed
 * {@code "+7"}, and {@code "%EF%BC%95"} where it claimed {@code "５"}. Three of fifteen rows
 * pinned strings no HTTP client can put in a parameter value, and the {@code " "} row was measuring
 * a lone {@code +}. Sent the way a client actually sends them, the route as it stood before
 * CTX-API-R2 answered <b>200 with seven packs</b> to {@code +7}. The census is now built with
 * {@code .param(...)}, which is the decoded value a servlet container hands the controller. The one
 * row still built through the URI is {@link #aPercentEncodedValueIsTransportAndNotTheValue}, and it
 * is there to hold that distinction in place rather than to add a spelling.
 *
 * <p><b>{@code assertBodyIsClean} was vacuous on an empty body.</b> Every {@code doesNotContain}
 * passes trivially over zero bytes, and the helper was being called on the 406 — which has no body
 * by design — twice, under a comment claiming the 406 body was being checked for internal detail.
 * The helper now refuses an empty body outright; a response that is meant to have none goes through
 * {@link #assertBodyIsAbsent}, which says so.
 */
@AutoConfigureMockMvc
@ExtendWith(LoggerLevelIsolation.class)
class ContextHttpErrorSurfaceTest extends ContextProbeFixture {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ExpectedHttpErrorLog expectedErrors;

  /** The one shape every refused spelling of {@code limit} produces. */
  private static final String ONE_LIMIT_CONTRACT = "400 VALIDATION_ERROR violations=1";

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
   * <p>The list hits different layers on purpose: a value no parser accepts ({@code abc},
   * {@code 5.5}), a value in range for a {@code long} and out of range for this route
   * ({@code 2147483648}), a value that is in range and forbidden ({@code 0}, {@code 101}), a
   * repeated parameter that arrives as {@code "1,2"}, an empty value, a hexadecimal literal, a
   * signed value, a Unicode digit that looks like a number to a human and is not one to
   * {@code Integer.parseInt}, and two SQL fragments.
   *
   * <p><b>CTX-09B-2 is closed, and the census is what closed it.</b> The finding was that one
   * parameter answered in three contracts and one of them was a 200: {@code ?limit=} fell through
   * to Spring's {@code defaultValue} and served a page of twenty to a caller whose variable had
   * interpolated to nothing, and {@code ?limit=0x10} was read by the type converter as sixteen.
   * Both were served with a page size the caller never named, by a route whose own contract says
   * out of range is <em>refused rather than clamped</em> on the stated grounds that a caller who
   * silently received a narrower answer would have no way to know. Five rows below changed:
   *
   * <pre>
   *   "0"     400 BAD_REQUEST violations=0   ->  400 VALIDATION_ERROR violations=1
   *   "-1"    400 BAD_REQUEST violations=0   ->  400 VALIDATION_ERROR violations=1
   *   "101"   400 BAD_REQUEST violations=0   ->  400 VALIDATION_ERROR violations=1
   *   ""      200 &lt;no code&gt;                ->  400 VALIDATION_ERROR violations=1
   *   "0x10"  200 &lt;no code&gt;                ->  400 VALIDATION_ERROR violations=1
   * </pre>
   *
   * <p>The first three moved because the refusal is now the controller's rather than a bean
   * validator's: {@code @Min}/{@code @Max} on a method parameter raises
   * {@code HandlerMethodValidationException}, which no handler claimed, so an out-of-range number
   * rendered as a bare {@code BAD_REQUEST} with an empty {@code violations} array while an
   * unparseable one rendered as {@code VALIDATION_ERROR} with a populated one. One parameter, two
   * bodies, for two kinds of the same mistake.
   *
   * <p>What the census asserts now is stronger than "three shapes became one". Every one of the
   * fifteen produces the identical status, the identical {@code code} and exactly one violation
   * naming {@code limit}; only the sentence inside the violation varies, which is where a client
   * parses it and not where a client branches. That is asserted as an exact map, so a row that
   * regressed to a 200 names itself.
   */
  @Test
  @DisplayName("The limit parameter answers in exactly one contract, over fifteen spellings")
  void theLimitParameterAnswersInOneContract() throws Exception {
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
            "５",
            "1;DROP TABLE context_packs",
            "1 OR 1=1");

    int packsBefore = packCount();
    Map<String, String> census = new LinkedHashMap<>();
    for (String value : spellings) {
      MvcResult result =
          mvc.perform(get(url(aliceProject)).param("limit", value).with(TestIdentity.as(alice)))
              .andReturn();
      census.put(value, shapeOf(result));
      assertBodyIsClean("limit=" + value, result);
    }

    // Property first. Whatever the contracts turn out to be, none may be a server fault: a client's
    // mis-spelled query parameter reported as a 500 is a stack trace in the log and a wrong answer.
    census.forEach(
        (value, shape) ->
            assertThat(shape)
                .as("limit=%s must be a client error, never a server fault", value)
                .doesNotStartWith("5"));

    // The census, pinned entry by entry rather than as a count of distinct shapes. A count would
    // stay green if two inputs swapped contracts with each other, and swapping a refusal for a 200
    // is precisely the change this test exists to catch.
    Map<String, String> expected = new LinkedHashMap<>();
    for (String value : spellings) {
      expected.put(value, ONE_LIMIT_CONTRACT);
    }
    assertThat(census)
        .as("CTX-09B-2 closed: one parameter, one contract, over every spelling that reaches it")
        .containsExactlyInAnyOrderEntriesOf(expected);

    assertThat(census.values().stream().distinct().toList())
        .as("and the count agrees with the map: one shape, not three")
        .hasSize(1);

    // The violation names the parameter in every case. Without this the map above would be
    // satisfied by fifteen refusals that each blamed a different field.
    for (String value : spellings) {
      MvcResult result =
          mvc.perform(get(url(aliceProject)).param("limit", value).with(TestIdentity.as(alice)))
              .andReturn();
      assertThat(json.readTree(result.getResponse().getContentAsString()).get("violations").get(0)
              .get("field").asText())
          .as("the refusal for limit=%s must name limit", value)
          .isEqualTo("limit");
    }

    // What used to be the third contract. ?limit= is now a refusal, so it can no longer be
    // confused with the absent parameter, and that difference is the finding's whole substance:
    // absent means "no number named" and is answered with the default; empty means "the caller
    // wrote the parameter and got the value wrong".
    MvcResult emptyValue =
        mvc.perform(get(url(aliceProject)).param("limit", "").with(TestIdentity.as(alice)))
            .andReturn();
    assertThat(emptyValue.getResponse().getStatus())
        .as("an empty limit is the caller's mistake, not a spelling of 'no limit'")
        .isEqualTo(400);
    mvc.perform(get(url(aliceProject)).with(TestIdentity.as(alice)))
        .andExpect(status().isOk());

    // The route still works and the table is still there. The injection spellings above are the
    // ones that would have removed it.
    assertThat(packCount())
        .as("the parameter was bound, not concatenated into a statement")
        .isEqualTo(packsBefore);
  }

  /**
   * The accepted end of the range, so the refusals above are known to be about the values and not
   * about a route that refuses everything.
   *
   * <p>{@code 01} and {@code 0000000005} are here for a second reason. The route's grammar accepts
   * a leading zero and reads it as decimal — {@code 01} is one, never octal and never hex — while
   * {@code 00000000005} is eleven characters and is refused as a format error though it plainly
   * denotes five. That is a rule about length rather than about value, it is written down in
   * {@code resolveLimit}'s javadoc as a deliberate cost, and pinning both sides of it here is what
   * would make a change to that boundary visible instead of quiet.
   */
  @Test
  @DisplayName("The accepted end of the range really is accepted, so the refusals mean something")
  void theAcceptedLimitsAreAccepted() throws Exception {
    for (String value : List.of("1", "20", "100", "01", "0000000005")) {
      mvc.perform(get(url(aliceProject)).param("limit", value).with(TestIdentity.as(alice)))
          .andExpect(status().isOk());
    }
    mvc.perform(get(url(aliceProject)).param("limit", "00000000005").with(TestIdentity.as(alice)))
        .andExpect(status().isBadRequest());
  }

  /**
   * The one row in this file still built through the URI, and what it is for.
   *
   * <p>It is not a sixteenth spelling. It is the measurement that keeps the census honest about
   * what it is measuring, because this file spent its whole life pinning the wrong side of it.
   *
   * <p>{@code %32%30} is nine characters. As a <em>parameter value</em> it is those nine characters
   * and nothing else, and the route refuses it, because {@code %32%30} is not how anyone spells
   * twenty. As a <em>query string</em> on the wire it is percent-encoding for {@code 20}, the
   * container decodes it before the controller ever sees it, and the route serves twenty packs.
   * Both answers are right. They are answers to different questions, and a test that builds its
   * inputs by encoding them into the URI is asking the second question while claiming to ask the
   * first — which is exactly how the old census came to pin {@code "%2B7"} under the label
   * {@code "+7"}.
   *
   * <p>So: same nine characters, two builders, two contracts, asserted together. If these two ever
   * agree, one of the two layers has stopped doing its job and the census above has stopped meaning
   * what it says.
   */
  @Test
  @DisplayName("Percent-encoding is transport: the same nine characters answer differently by layer")
  void aPercentEncodedValueIsTransportAndNotTheValue() throws Exception {
    MvcResult asValue =
        mvc.perform(get(url(aliceProject)).param("limit", "%32%30").with(TestIdentity.as(alice)))
            .andReturn();
    // get(URI) is used rather than get(String): the String overload runs .encode() over the
    // template, which would turn this already-encoded query into %2532%2530 and measure a third
    // thing nobody asked about.
    MvcResult onTheWire =
        mvc.perform(
                get(URI.create(url(aliceProject) + "?limit=%32%30")).with(TestIdentity.as(alice)))
            .andReturn();

    assertThat(asValue.getResponse().getStatus())
        .as("as a parameter value, %32%30 is nine characters and is not a decimal integer")
        .isEqualTo(400);
    assertThat(onTheWire.getResponse().getStatus())
        .as("on the wire, %32%30 is the two characters 2 and 0, and the route serves twenty")
        .isEqualTo(200);
  }

  // ------------------------------------------------------------------ the method and media surface

  /**
   * Methods and media types the routes do not serve.
   *
   * <p>The compile route is the interesting one: it is a {@code POST} that writes, and every other
   * verb aimed at it must be refused without writing. The two GET routes are checked for the same
   * thing from the other direction — a {@code PUT} or {@code DELETE} that happened to reach a
   * handler would be a mutation on a read path.
   *
   * <p>None of these requests sets an {@code Accept} header, and that is why every one of them has
   * a body to check. The {@code Accept: application/xml} case has moved to
   * {@link #anUnacceptableAcceptHeaderIsCountedNotTraced}, where its empty body is asserted as
   * empty instead of being handed to a helper that reads nothing.
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
   * <b>CTX-09B-3, reproduced and closed — and it was worse than the log noise it was written up as.</b>
   *
   * <p>The finding as originally recorded: {@code Accept: application/xml} on any of these routes
   * produced a 406 and cost a WARN with a fifty-frame stack trace from
   * {@code ExceptionHandlerExceptionResolver}, on a condition the caller chooses by setting one
   * header, repeated for every request. That was true and it was not the whole of it.
   *
   * <p>What was actually happening is that Spring does not re-dispatch an exception thrown while
   * writing an {@code @ExceptionHandler}'s own return value. It logs {@code Failure in @ExceptionHandler}
   * and returns null, and the original exception then escapes the resolver entirely. So a mapped
   * error whose {@code ApiError} could not be serialised did not merely make noise — <b>it lost its
   * own response</b>. On a real Tomcat, {@code GET /api/projects/&lt;unknown id&gt;} answered
   * <b>500 with no body</b> under {@code Accept: application/xml} and 404 with the documented
   * {@code NOT_FOUND} body under any other header. A header the caller chose was deciding whether
   * this API reported a client error or a server fault. MockMvc could not see that: it rethrows the
   * escape out of {@code perform} as a {@code ServletException}, which is a different observation
   * from an error page, and that is why the wire half of this is pinned on a real socket by
   * {@code ExpectedHttpErrorWireContractTest} rather than here.
   *
   * <p>Both halves are now fixed in {@code ApiExceptionHandler}: every error body leaves through a
   * helper that refuses to hand Spring a body Spring cannot write, keeping the status and dropping
   * the body, and the 406 branch counts the occurrence through {@link ExpectedHttpErrorLog} instead
   * of tracing it. This test asserts what now holds, in three parts.
   *
   * <ol>
   *   <li><b>No traces.</b> Zero WARN-or-above events carrying a throwable, over the whole window.
   *   <li><b>The status is the route's, not the header's.</b> Four error paths answer the same
   *       status under {@code application/json} and {@code application/xml}. The two success paths
   *       answer 200 and 406, which is ordinary content negotiation and the one case where the
   *       header legitimately decides.
   *   <li><b>Counted, not silenced.</b> The tally for the 406 rises by exactly the number of 406s.
   * </ol>
   *
   * <p>Zero is a number this project has manufactured before, so the "no traces" assertion carries
   * its own positive control: a throwable is logged deliberately through the same root logger and
   * the same predicate is required to find it. Without that, the filter could be matching nothing
   * for any reason at all — a renamed exception, a detached appender, a level that never reached
   * WARN — and would report success.
   */
  @Test
  @DisplayName("CTX-09B-3 closed: an unacceptable Accept header is counted, and decides no status")
  void anUnacceptableAcceptHeaderIsCountedNotTraced() throws Exception {
    UUID packId = compileForAlice("ALICE-ACCEPT");
    UUID missingPack = UUID.randomUUID();
    UUID missingProject = UUID.randomUUID();
    int linesBefore = captured.list.size();
    long notAcceptableBefore =
        expectedErrors.countOf(406, "HttpMediaTypeNotAcceptableException");

    // Four error paths. Each is performed twice, differing only in the Accept header, and the
    // status must not move. These are the paths on which the escape used to happen.
    Map<String, MockHttpServletRequestBuilder> errorPaths = new LinkedHashMap<>();
    errorPaths.put("404 unknown pack", get(url(aliceProject) + "/" + missingPack));
    errorPaths.put("404 unknown project", get(url(missingProject)));
    errorPaths.put("400 unconvertible path variable", get(url(aliceProject) + "/compile"));
    errorPaths.put(
        "400 unreadable body",
        post(url(aliceProject) + "/compile")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"taskReference\":"));

    Map<String, String> statusByAccept = new LinkedHashMap<>();
    for (Map.Entry<String, MockHttpServletRequestBuilder> path : errorPaths.entrySet()) {
      MvcResult asJson =
          mvc.perform(
                  path.getValue().with(TestIdentity.as(alice)).accept(MediaType.APPLICATION_JSON))
              .andReturn();
      MvcResult asXml =
          mvc.perform(
                  path.getValue().with(TestIdentity.as(alice)).accept(MediaType.APPLICATION_XML))
              .andReturn();
      statusByAccept.put(
          path.getKey(),
          asJson.getResponse().getStatus() + " json / " + asXml.getResponse().getStatus() + " xml");
      assertBodyIsClean(path.getKey() + " under Accept: application/json", asJson);
      // The XML half has no body by design — there is no representation this caller would take —
      // so it is asserted empty rather than swept for strings that cannot be in zero bytes.
      assertBodyIsAbsent(path.getKey() + " under Accept: application/xml", asXml);
    }

    assertThat(statusByAccept)
        .as(
            "a header the caller sets must not change what this API reports happened; each of"
                + " these answered 500 with no body under application/xml before the fix")
        .containsExactly(
            Map.entry("404 unknown pack", "404 json / 404 xml"),
            Map.entry("404 unknown project", "404 json / 404 xml"),
            Map.entry("400 unconvertible path variable", "400 json / 400 xml"),
            Map.entry("400 unreadable body", "400 json / 400 xml"));

    // The two paths where the header legitimately decides, because there really is a body the
    // caller will not take: a successful response. 406 is the right answer and the only one.
    List<MvcResult> notAcceptable = new ArrayList<>();
    for (String path : List.of(url(aliceProject), url(aliceProject) + "/" + packId)) {
      notAcceptable.add(
          mvc.perform(get(path).with(TestIdentity.as(alice)).accept(MediaType.APPLICATION_XML))
              .andExpect(status().isNotAcceptable())
              .andReturn());
      mvc.perform(get(path).with(TestIdentity.as(alice)).accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk());
    }
    for (MvcResult result : notAcceptable) {
      assertBodyIsAbsent("406", result);
    }

    // (1) No traces. Every WARN-or-above event in the window, with a throwable attached — not
    // filtered by exception name, because a filter that names the exception it expects cannot see
    // the one it does not.
    List<String> traces =
        captured.list.stream()
            .skip(linesBefore)
            .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
            .filter(event -> event.getThrowableProxy() != null)
            .map(event -> event.getLoggerName() + " @" + event.getLevel() + ": "
                + LogCapture.lineOf(event))
            .toList();
    assertThat(traces)
        .as(
            "CTX-09B-3: the caller sets a header, the caller does not get to write stack traces"
                + " into an operator's log. Ten requests above, none of them a fault of ours.")
        .isEmpty();

    // The positive control for that zero. If the predicate above cannot find a trace that is
    // definitely there, its emptiness says nothing about the requests.
    int beforeControl = captured.list.size();
    LoggerFactory.getLogger(ContextHttpErrorSurfaceTest.class)
        .warn("negative-control trace", new IllegalStateException("surface-control-zqxw-771204"));
    assertThat(
            captured.list.stream()
                .skip(beforeControl)
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .filter(event -> event.getThrowableProxy() != null)
                .map(LogCapture::lineOf)
                .toList())
        .as("the filter that reported zero above must be able to report one")
        .hasSize(1)
        .allSatisfy(line -> assertThat(line).contains("surface-control-zqxw-771204"));

    // (3) Counted, not silenced. Two routes, one 406 each, and the tally rose by two.
    assertThat(expectedErrors.countOf(406, "HttpMediaTypeNotAcceptableException"))
        .as("the 406s are recorded; quieting a trace must not mean losing the signal")
        .isEqualTo(notAcceptableBefore + 2);

    // And what the tally writes carries nothing of the caller: a status and a fixed type name.
    List<String> tallyLines =
        captured.list.stream()
            .skip(linesBefore)
            .map(LogCapture::lineOf)
            .filter(line -> line.contains("HttpMediaTypeNotAcceptableException"))
            .toList();
    assertThat(tallyLines).as("the occurrence is written down somewhere").isNotEmpty();
    for (String line : tallyLines) {
      assertThat(line)
          .as("the line that replaced the trace must not describe the project or the caller")
          .doesNotContain("Alice surface")
          .doesNotContain("ALICE-ACCEPT")
          .doesNotContain(aliceProject.toString())
          .doesNotContain("\tat ");
    }
  }

  /**
   * <b>CTX-09B-3b closed: a refused {@code limit} keeps its 400 whatever the caller accepts.</b>
   *
   * <p>This is the same attack that found the defect, inverted in place. Same three requests, same
   * order, same two-header control; only the assertion about the third one changed, because what
   * the third one does changed. It was called
   * {@code aRefusedLimitStillEscapesTheResolverUnderAnUnacceptableAccept} while it asserted the
   * escape, and it is renamed rather than replaced — a method whose name says "still escapes" while
   * asserting that it does not is the kind of sentence that stops the next reader checking.
   *
   * <p><b>What the defect was.</b> {@code ContextPackController.InvalidLimitAdvice} — added by
   * CTX-API-R2 to give {@code limit} its single contract — built its {@code ResponseEntity} with a
   * body directly, rather than through the boundary that refuses to hand Spring a body Spring
   * cannot write. That boundary was a <em>private</em> method on {@code ApiExceptionHandler}, so a
   * second advice could not reach it even to try. So a refused {@code limit} under an {@code Accept}
   * header naming no {@code +json} type reproduced the original defect exactly: the {@code ApiError}
   * could not be serialised, the write failed inside the handler, Spring did not re-dispatch, and
   * the exception escaped the resolver — which under MockMvc surfaced as
   *
   * <pre>
   *   jakarta.servlet.ServletException: Request processing failed:
   *     com.vibecode.context.web.ContextPackController$InvalidLimitException: limit must be at least 1
   * </pre>
   *
   * and under a real container as <b>500 with no body, for a request whose true answer is 400</b>.
   * Two tasks that were each correct alone; the defect lived only in the seam between them, and
   * neither could see it before both were merged.
   *
   * <p><b>What closed it.</b> The negotiation decision moved out of {@code ApiExceptionHandler} into
   * {@code ApiErrorResponder}, a collaborator both advices hold. Not a second mechanism for this
   * endpoint — the <em>same</em> one, made reachable. So the answer here is the answer the shared
   * boundary was already giving on every other error path: <b>keep the status, omit the body the
   * caller could not have read, record the omission.</b> That is what the three assertions below
   * check, and the tally assertion is the one that distinguishes "the body was deliberately
   * withheld" from "the body silently went missing".
   *
   * <p><b>What would have to be true for this to fail.</b> The advice would have to build its own
   * body again — {@code ResponseEntity.status(BAD_REQUEST).body(...)}, one line, the shape of an
   * ordinary refactor. That mutation was applied and this test failed on the {@code
   * assertThatCode(...).doesNotThrowAnyException()} before the fix was restored.
   */
  @Test
  @DisplayName("CTX-09B-3b closed: a refused limit keeps its 400 under an unacceptable Accept")
  void aRefusedLimitKeepsItsStatusUnderAnUnacceptableAccept() throws Exception {
    MvcResult asJson =
        mvc.perform(
                get(url(aliceProject))
                    .param("limit", "0")
                    .with(TestIdentity.as(alice))
                    .accept(MediaType.APPLICATION_JSON))
            .andReturn();
    assertThat(asJson.getResponse().getStatus())
        .as("the control: this is a 400 for every caller who accepts something we can write")
        .isEqualTo(400);
    assertBodyIsClean("refused limit under Accept: application/json", asJson);
    assertThat(shapeOf(asJson)).isEqualTo(ONE_LIMIT_CONTRACT);

    MvcResult asProblemJson =
        mvc.perform(
                get(url(aliceProject))
                    .param("limit", "0")
                    .with(TestIdentity.as(alice))
                    .accept(MediaType.valueOf("application/problem+json")))
            .andReturn();
    assertThat(asProblemJson.getResponse().getStatus())
        .as("and for a +json caller, which Jackson writes")
        .isEqualTo(400);
    // Stronger than the status alone, and stronger than this control used to be: a +json caller is
    // owed the whole body, not merely the same number. R2-A was a body quietly stopping, not a
    // status moving, so a status-only control could not have seen it.
    assertThat(normaliseTimestamp(asProblemJson.getResponse().getContentAsString()))
        .as("a +json caller accepts something we can write and must receive it in full")
        .isEqualTo(normaliseTimestamp(asJson.getResponse().getContentAsString()));

    // The attack. It threw before the fix; the first assertion is that it no longer does, made
    // explicitly rather than by letting the next line fail with a confusing message.
    long omittedBefore = expectedErrors.countOf(400, "BodyOmittedForAcceptHeader");
    int linesBefore = captured.list.size();
    MvcResult[] asXml = new MvcResult[1];
    org.assertj.core.api.Assertions.assertThatCode(
            () ->
                asXml[0] =
                    mvc.perform(
                            get(url(aliceProject))
                                .param("limit", "0")
                                .with(TestIdentity.as(alice))
                                .accept(MediaType.APPLICATION_XML))
                        .andReturn())
        .as(
            "CTX-09B-3b: InvalidLimitAdvice now answers through the shared boundary, so the write"
                + " is never attempted in a type nothing here can produce and nothing escapes the"
                + " resolver. On a real container this is what stops the 400 becoming a 500.")
        .doesNotThrowAnyException();

    assertThat(asXml[0].getResponse().getStatus())
        .as("a header the caller sets is a statement about representations, not about what happened")
        .isEqualTo(400);
    assertBodyIsAbsent("refused limit under Accept: application/xml", asXml[0]);

    // Withheld, not lost. Without this the empty body above would be satisfied by a response that
    // fell over quietly, which is the failure this whole task is about.
    assertThat(expectedErrors.countOf(400, "BodyOmittedForAcceptHeader"))
        .as("the omission is a decision the boundary recorded, not a body that went missing")
        .isEqualTo(omittedBefore + 1);

    // And it cost the operator no stack frames. LOG-HTTP-R1's guarantee, on the path that used to
    // bypass it: the escape it replaced wrote a 190-frame WARN from the resolver.
    assertThat(
            captured.list.stream()
                .skip(linesBefore)
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .filter(event -> event.getThrowableProxy() != null)
                .map(LogCapture::lineOf)
                .toList())
        .as("a refused limit is the caller's mistake and does not write a trace for an operator")
        .isEmpty();
  }

  /**
   * The required matrix: every behaviour class of {@code limit} crossed with every {@code Accept}
   * header a caller realistically sends, pinned as an exact map of <b>status and body</b>.
   *
   * <p>Status alone is what let the original three-contract defect hide — {@code ?limit=} answered
   * 200 with a page nobody asked for, and a test that only read statuses would have called that
   * fine. So every cell below carries what the body was: how many packs a 200 returned, which
   * {@code code} and how many violations a 400 named, or the fact that there was no body at all.
   *
   * <p>Three behaviour classes, and the third is the one this task exists for:
   *
   * <ul>
   *   <li><b>A limit this route honours</b> (absent, {@code 20}) is a 200 with the page — except
   *       under {@code application/xml}, where the <em>successful</em> representation genuinely
   *       cannot be written and 406 is the right and only answer. That is the case where the
   *       {@code Accept} header legitimately decides, and it stays.
   *   <li><b>A limit this route refuses</b> ({@code ""}, {@code abc}, {@code 0x10}, {@code 0},
   *       {@code -1}, {@code 101}) is one 400 with one shape, for every caller who accepts
   *       something we can write.
   *   <li><b>The same refusal under {@code application/xml}</b> is still 400, with no body. Not
   *       406: the caller's header did not make the limit valid. Not 500: their header is not a
   *       fault of ours. This row is the defect, and every one of its six cells used to be an
   *       exception escaping the resolver.
   * </ul>
   *
   * <p>{@code &#42;/&#42;} and a missing header are in the matrix because they are the two spellings of
   * "no preference", and a check that treated either as a refusal would drop bodies from the
   * ordinary caller — the failure in the opposite direction, which a matrix of only {@code xml}
   * and {@code json} could not see.
   */
  @Test
  @DisplayName("Eight spellings of limit crossed with four Accept headers: status and body, pinned")
  void theLimitContractHoldsAcrossEveryAcceptHeader() throws Exception {
    compileForAlice("ALICE-MATRIX-1");
    compileForAlice("ALICE-MATRIX-2");

    // null is the absent parameter, which is the one spelling that means "no number named".
    List<String> limits =
        new ArrayList<>(Arrays.asList(null, "20", "", "abc", "0x10", "0", "-1", "101"));
    Map<String, MediaType> accepts = new LinkedHashMap<>();
    accepts.put("<no Accept>", null);
    accepts.put("application/json", MediaType.APPLICATION_JSON);
    accepts.put("application/xml", MediaType.APPLICATION_XML);
    accepts.put("*/*", MediaType.ALL);

    int linesBefore = captured.list.size();
    Map<String, String> matrix = new LinkedHashMap<>();
    for (String limit : limits) {
      for (Map.Entry<String, MediaType> accept : accepts.entrySet()) {
        String cell = "limit=" + (limit == null ? "<absent>" : "'" + limit + "'")
            + " accept=" + accept.getKey();
        MockHttpServletRequestBuilder request = get(url(aliceProject)).with(TestIdentity.as(alice));
        if (limit != null) {
          request = request.param("limit", limit);
        }
        if (accept.getValue() != null) {
          request = request.accept(accept.getValue());
        }
        MvcResult[] result = new MvcResult[1];
        MockHttpServletRequestBuilder built = request;
        // The escape is a thrown exception, not a status, so it has to be asserted as one. Every
        // xml cell in the refusal rows threw here before the fix.
        org.assertj.core.api.Assertions.assertThatCode(() -> result[0] = mvc.perform(built).andReturn())
            .as("%s must produce a response, not an exception out of the dispatcher", cell)
            .doesNotThrowAnyException();
        matrix.put(cell, listShapeOf(result[0]));
        if (result[0].getResponse().getContentAsString().isEmpty()) {
          assertBodyIsAbsent(cell, result[0]);
        } else {
          assertBodyIsClean(cell, result[0]);
        }
      }
    }

    // Property first, before the exact map: not one cell of thirty-two may be a server fault.
    matrix.forEach(
        (cell, shape) ->
            assertThat(shape).as("%s must never be a server fault", cell).doesNotStartWith("5"));

    Map<String, String> expected = new LinkedHashMap<>();
    for (String limit : limits) {
      boolean honoured = limit == null || limit.equals("20");
      String label = limit == null ? "<absent>" : "'" + limit + "'";
      String written = honoured ? "200 packs=2" : ONE_LIMIT_CONTRACT;
      expected.put("limit=" + label + " accept=<no Accept>", written);
      expected.put("limit=" + label + " accept=application/json", written);
      expected.put("limit=" + label + " accept=*/*", written);
      // The one column where the two classes diverge. A successful page really has no
      // representation this caller would take, so 406 is correct; a refusal's status is not a
      // representation and is kept.
      expected.put(
          "limit=" + label + " accept=application/xml",
          honoured ? "406 <empty body>" : "400 <empty body>");
    }
    assertThat(matrix)
        .as(
            "CTX-09B-3b closed: no combination of limit and Accept turns a client error into a"
                + " server fault, and the semantic status is the same in every column")
        .containsExactlyInAnyOrderEntriesOf(expected);

    // Not one of the thirty-two wrote a stack frame an operator has to read.
    assertThat(
            captured.list.stream()
                .skip(linesBefore)
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .filter(event -> event.getThrowableProxy() != null)
                .map(LogCapture::lineOf)
                .toList())
        .as("thirty-two client requests, none of them a fault of ours, none of them traced")
        .isEmpty();
  }

  /**
   * The {@code Accept} header itself, attacked — because "any {@code Accept} header" is the claim,
   * and four well-formed values are not "any".
   *
   * <p>One refused {@code limit}, thirteen headers. The status must be 400 in every row; the body
   * is present exactly when some listed type is one this API can write, and absent otherwise.
   * Nothing here may throw, and nothing here may be a 406 — the caller's header did not make
   * {@code limit=0} a valid page size.
   *
   * <p>The rows are chosen from what actually breaks a negotiation check: a type list rather than a
   * single type, wildcards at both levels, quality parameters including {@code q=0}, whitespace and
   * casing, <b>ten charsets straddling the five Jackson can encode</b>, and a header that is not a
   * media type at all.
   *
   * <p><b>Review finding R3-A, and it landed on this test by name.</b> This method declared its job
   * as attacking the header itself, and then carried exactly one charset row —
   * {@code application/json;charset=UTF-8}, one of the five Jackson accepts. It could not have
   * failed for the reason it existed. That is the standing lesson of this project applied to the
   * test written to embody it: when an assertion passes, ask what would have to be true for it to
   * fail, and if the answer is an input the test never sends, it is decoration. The charsets that
   * break the encoder are now sent.
   * The last two are the ones a check written with {@code contains("json")} would pass and a check
   * written with {@code MediaType.parseMediaTypes} must survive: an unparseable header makes
   * Spring's own negotiation impossible too, so the only safe reading of it is "send no body", and
   * the status still stands.
   */
  @Test
  @DisplayName("A refused limit stays a 400 under every Accept header, charset and all")
  void theRefusalSurvivesEveryShapeOfAcceptHeader() throws Exception {
    // header -> whether some type in it is one this API can write an ApiError as
    Map<String, Boolean> headers = new LinkedHashMap<>();
    headers.put("application/json", true);
    headers.put("application/problem+json", true);
    headers.put("application/vnd.vibecode.v1+json", true);
    headers.put("*/*", true);
    headers.put("application/*", true);
    headers.put("APPLICATION/JSON", true);
    headers.put("  application/json  ", true);
    headers.put("application/json;charset=UTF-8", true);
    // Review finding R3-A. This row used to be the only charset in the file, and it passed for a
    // reason that had nothing to do with the guard being correct: UTF-8 is one of the five charsets
    // Jackson has a JsonEncoding for. Ask what would have to be true for it to fail and the answer
    // is "any charset but those five", which the row never sent. The rows below send them. Before
    // the fix every one of the false ones answered 500 with no body on a real container, because
    // the check compared media types with isCompatibleWith — which ignores parameters — while
    // Spring's write path asks canWrite with the caller's full type, charset included.
    headers.put("application/json;charset=US-ASCII", true);
    headers.put("application/json;charset=UTF-16BE", true);
    headers.put("application/json;charset=UTF-16LE", true);
    headers.put("application/json;charset=UTF-32BE", true);
    headers.put("application/json;charset=ISO-8859-1", false);
    headers.put("application/json;charset=UTF-16", false);
    headers.put("application/json;charset=UTF-32", false);
    headers.put("application/json;charset=windows-1252", false);
    headers.put("application/json;charset=Shift_JIS", false);
    headers.put("application/problem+json;charset=ISO-8859-1", false);
    // Spring selects one candidate and asks the converters about that one, so a writable
    // alternative behind an unwritable one is not a safe bet. Refused, body dropped, status kept.
    headers.put("application/json;charset=ISO-8859-1, application/json", false);
    headers.put("application/xml, application/json;q=0.9", true);
    // q=0 says "I would rather have nothing", and this API has nothing else to offer. Measured
    // rather than reasoned about: Spring's own negotiation does not drop a q=0 candidate before
    // selecting a converter, so the body is written and the two sides of the check agree. They have
    // to agree — this class's check and Spring's are the same comparison over the same producible
    // list, which is exactly why the list is derived from the converters instead of written down.
    headers.put("application/json;q=0", true);
    headers.put("application/xml;q=1, application/json;q=0", true);
    headers.put("application/xml", false);
    headers.put("text/*", false);
    headers.put("application/xml, text/csv;q=0.8, image/png", false);
    headers.put("this is not a media type", false);

    int linesBefore = captured.list.size();
    Map<String, String> answers = new LinkedHashMap<>();
    for (String header : headers.keySet()) {
      MvcResult[] result = new MvcResult[1];
      org.assertj.core.api.Assertions.assertThatCode(
              () ->
                  result[0] =
                      mvc.perform(
                              get(url(aliceProject))
                                  .param("limit", "0")
                                  .with(TestIdentity.as(alice))
                                  .header(HttpHeaders.ACCEPT, header))
                          .andReturn())
          .as("Accept: %s must produce a response, not an exception", header)
          .doesNotThrowAnyException();
      answers.put(header, listShapeOf(result[0]));
      if (result[0].getResponse().getContentAsString().isEmpty()) {
        assertBodyIsAbsent("Accept: " + header, result[0]);
      } else {
        assertBodyIsClean("Accept: " + header, result[0]);
      }
    }

    Map<String, String> expected = new LinkedHashMap<>();
    headers.forEach(
        (header, writable) ->
            expected.put(header, writable ? ONE_LIMIT_CONTRACT : "400 <empty body>"));
    assertThat(answers)
        .as(
            "the status is the route's answer and the header only decides whether a body can be"
                + " carried; no header of any shape may move it off 400")
        .containsExactlyInAnyOrderEntriesOf(expected);

    assertThat(
            captured.list.stream()
                .skip(linesBefore)
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .filter(event -> event.getThrowableProxy() != null)
                .map(LogCapture::lineOf)
                .toList())
        .as("thirteen client mistakes, no stack frames")
        .isEmpty();
  }

  /**
   * <b>Review finding R3-C: the stack-trace flood was reachable by one header, on the success
   * path.</b>
   *
   * <p>A limit this route <em>honours</em> under a charset Jackson cannot encode is a genuine 406 —
   * the page really has no representation this caller would take — and the 406 is not what was
   * wrong. What was wrong is what it cost. {@code ApiExceptionHandler#notAcceptable} asked whether
   * the caller accepts our representation, got {@code true} from a check that ignored the charset,
   * concluded the fault must be ours, wrote an {@code ERROR} with the throwable, and then tried to
   * send a body — which failed, adding the resolver's ~190-frame {@code Failure in @ExceptionHandler}
   * WARN on top. Per request. That is precisely the unbounded, client-triggerable flood
   * LOG-HTTP-R1 exists to prevent, arriving through a door LOG-HTTP-R1 did not measure.
   *
   * <p>And {@link #aHundredRefusedLimitsUnderAnUnacceptableAcceptCostNoStackTrace} reported zero
   * throughout, because it only ever sends {@code application/xml} — a type that is not a candidate
   * at all, so it never reached the charset question. A guard with a parameter, and the test never
   * mutated the parameter.
   *
   * <p>One fix closes it: once the negotiation decision asks the converters with the caller's full
   * media type, this header answers {@code false}, {@code notAcceptable} takes its
   * caller's-mistake branch, and the request is counted rather than traced. So this test asserts
   * the log, not just the status — the status was already right.
   *
   * <p>Each request is performed inside a try/catch rather than an assertion, deliberately: the
   * failure being measured is an exception escaping the dispatcher, and a measurement that aborts
   * on the first escape cannot report how many there were. The outcomes are asserted afterwards.
   */
  @Test
  @DisplayName("R3-C: a honoured limit under an unencodable charset is a quiet 406, not a flood")
  void aHonouredLimitUnderAnUnwritableCharsetIsCountedNotTraced() throws Exception {
    compileForAlice("ALICE-CHARSET");
    int linesBefore = captured.list.size();
    long notAcceptableBefore = expectedErrors.countOf(406, "HttpMediaTypeNotAcceptableException");

    List<String> outcomes = new ArrayList<>();
    for (int i = 0; i < 36; i++) {
      try {
        outcomes.add(
            String.valueOf(
                mvc.perform(
                        get(url(aliceProject))
                            .param("limit", "20")
                            .with(TestIdentity.as(alice))
                            .header(HttpHeaders.ACCEPT, "application/json;charset=ISO-8859-1"))
                    .andReturn()
                    .getResponse()
                    .getStatus()));
      } catch (Exception escaped) {
        outcomes.add("escaped:" + escaped.getClass().getSimpleName());
      }
    }

    assertThat(outcomes)
        .as("ISO-8859-1 is HTTP's own historical default text charset, not an exotic input")
        .hasSize(36)
        .containsOnly("406");

    List<String> traces =
        captured.list.stream()
            .skip(linesBefore)
            .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
            .filter(event -> event.getThrowableProxy() != null)
            .map(event -> event.getLoggerName() + " @" + event.getLevel() + ": "
                + LogCapture.lineOf(event))
            .toList();
    assertThat(traces)
        .as(
            "R3-C: thirty-six requests differing from an ordinary one by a charset must not write a"
                + " single frame. Before the fix these wrote ERRORs with the throwable and the"
                + " resolver's ~190-frame WARN on top, per request.")
        .isEmpty();

    // Counted, not silenced — the same signal the xml caller gets, for the same reason.
    assertThat(expectedErrors.countOf(406, "HttpMediaTypeNotAcceptableException"))
        .as("a 406 an operator can see is the point; silence would be the other way to get to zero")
        .isEqualTo(notAcceptableBefore + 36);

    // The positive control for that zero, in the same window and through the same predicate.
    int beforeControl = captured.list.size();
    LoggerFactory.getLogger(ContextHttpErrorSurfaceTest.class)
        .warn("charset-control", new IllegalStateException("charset-control-zqxw-118033"));
    assertThat(
            captured.list.stream()
                .skip(beforeControl)
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .filter(event -> event.getThrowableProxy() != null)
                .map(LogCapture::lineOf)
                .toList())
        .as("the filter that reported zero above must be able to report one")
        .hasSize(1)
        .allSatisfy(line -> assertThat(line).contains("charset-control-zqxw-118033"));
  }

  /**
   * A hundred refused limits under an unacceptable {@code Accept}, and what they cost an operator.
   *
   * <p>LOG-HTTP-R1 measured this for the 406 path and pinned it at zero frames. The {@code limit}
   * path was not going through that boundary, so it was not covered by that measurement: each of
   * these hundred requests wrote a 190-frame WARN from {@code ExceptionHandlerExceptionResolver}
   * and lost its response. This is the same measurement, on the path that used to bypass it.
   *
   * <p>The zero carries its own positive control, because zero is a number this project has
   * manufactured before: a throwable is logged deliberately through the same root logger and the
   * same predicate is required to find it. Without that, the filter could be reporting zero for a
   * detached appender or a level that never reached WARN and would look identical.
   */
  @Test
  @DisplayName("A hundred refused limits under Accept: xml cost a hundred 400s and no stack frame")
  void aHundredRefusedLimitsUnderAnUnacceptableAcceptCostNoStackTrace() throws Exception {
    int linesBefore = captured.list.size();
    long omittedBefore = expectedErrors.countOf(400, "BodyOmittedForAcceptHeader");
    List<Integer> statuses = new ArrayList<>();

    for (int i = 0; i < 100; i++) {
      statuses.add(
          mvc.perform(
                  get(url(aliceProject))
                      .param("limit", "0")
                      .with(TestIdentity.as(alice))
                      .accept(MediaType.APPLICATION_XML))
              .andReturn()
              .getResponse()
              .getStatus());
    }

    assertThat(statuses).hasSize(100).containsOnly(400);

    List<String> traces =
        captured.list.stream()
            .skip(linesBefore)
            .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
            .filter(event -> event.getThrowableProxy() != null)
            .map(event -> event.getLoggerName() + " @" + event.getLevel() + ": "
                + LogCapture.lineOf(event))
            .toList();
    assertThat(traces)
        .as("a hundred requests a caller chose to send, and not one frame for an operator to read")
        .isEmpty();

    // The signal that replaced them: one recorded omission per request, none of them silent.
    assertThat(expectedErrors.countOf(400, "BodyOmittedForAcceptHeader") - omittedBefore)
        .as("counted, not silenced — an operator can still see that bodies are being dropped")
        .isEqualTo(100);

    // The positive control for the zero above.
    int beforeControl = captured.list.size();
    LoggerFactory.getLogger(ContextHttpErrorSurfaceTest.class)
        .warn("limit-storm-control", new IllegalStateException("limit-control-zqxw-660418"));
    assertThat(
            captured.list.stream()
                .skip(beforeControl)
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .filter(event -> event.getThrowableProxy() != null)
                .map(LogCapture::lineOf)
                .toList())
        .as("the filter that reported zero above must be able to report one")
        .hasSize(1)
        .allSatisfy(line -> assertThat(line).contains("limit-control-zqxw-660418"));
  }

  // ------------------------------------------------------------------ the unreadable body

  /**
   * The {@code MALFORMED_REQUEST} handler, which nothing in this suite reached until now.
   *
   * <p><b>Why this test exists (review finding F3).</b> This class states as its second property
   * that "no error body carries internal detail — no package name, no SQL, no driver text, no
   * frame", and it was making that claim over a set of requests none of which reached the one
   * handler most likely to break it. Of everything it sent, the closest were a {@code text/plain}
   * body and a body with no content type, and both are refused at 415 before Jackson runs. Nothing
   * sent malformed JSON, nothing sent a body field of the wrong type, and nothing touched the
   * {@code budget} object at all.
   *
   * <p>That was demonstrated rather than argued. Replacing the handler's fixed sentence with
   * {@code exception.getMessage()} — a one-line change, the shape of an ordinary "make the error
   * more helpful" commit — left all 587 tests green while
   * {@code POST /api/projects/{id}/context/compile} answered:
   *
   * <pre>
   *   {"code":"MALFORMED_REQUEST","message":"JSON parse error: Cannot construct instance of
   *    `com.vibecode.context.web.ContextDtos$ContextBudgetRequest` (although at least one Creator
   *    exists): ..."}
   * </pre>
   *
   * <p>Four probes, chosen because each reaches a different Jackson failure and each leaks a
   * different kind of thing under that mutation:
   *
   * <ul>
   *   <li>truncated JSON — a parse error, which names no type but does name the parser's position;
   *   <li>{@code budget} as a string — a construction failure, which names
   *       {@code com.vibecode.context.web.ContextDtos$ContextBudgetRequest} in full;
   *   <li>{@code budget.maxItems} as a string — a coercion failure, which names
   *       {@code java.lang.Integer} <em>and echoes the caller's rejected value back to them</em>;
   *   <li>no body at all — which names the controller method's whole signature, return type
   *       included.
   * </ul>
   *
   * <p>The third of those is worth stating separately, because the reassurance that
   * "{@code INCLUDE_SOURCE_IN_LOCATION} is disabled so the request body cannot appear" covers only
   * parse errors. A deserialisation failure carries the offending value in its own message and is
   * not governed by that flag at all — which is why {@code needle} below is asserted absent
   * directly rather than being left to a Jackson default. That default is also worth naming as a
   * risk in its own right: nothing in this application's configuration or tests holds it in place.
   * It is a library default, and a library default is not a guarantee.
   *
   * <p>What would have to be true for this test to fail: the handler would have to put any part of
   * the exception it caught into the response. That is the entire failure mode, and it is a
   * one-line change away in both directions.
   */
  @Test
  @DisplayName("An unreadable body is refused without naming a type, a signature or the value sent")
  void aMalformedBodyNamesNoTypeAndEchoesNoValue() throws Exception {
    int before = packCount();
    String needle = "malformed_needle_zqxw_660913";

    Map<String, String> bodies = new LinkedHashMap<>();
    bodies.put("truncated json", "{\"taskReference\":\"" + needle + "\",");
    bodies.put("budget is a string", "{\"taskReference\":\"OK\",\"budget\":\"" + needle + "\"}");
    bodies.put(
        "budget.maxItems is a string",
        "{\"taskReference\":\"OK\",\"budget\":{\"maxItems\":\"" + needle + "\"}}");
    bodies.put("no body at all", "");

    Map<String, String> shapes = new LinkedHashMap<>();
    for (Map.Entry<String, String> body : bodies.entrySet()) {
      MvcResult result =
          mvc.perform(
                  post(url(aliceProject) + "/compile")
                      .with(TestIdentity.as(alice))
                      .with(csrf())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body.getValue()))
              .andReturn();
      shapes.put(body.getKey(), shapeOf(result));

      String content = result.getResponse().getContentAsString();
      assertBodyIsClean(body.getKey(), result);
      assertThat(content)
          .as("%s: the body must name no type, no frame and no field of ours", body.getKey())
          .doesNotContain("com.vibecode")
          .doesNotContain("java.")
          .doesNotContain("Jackson")
          .doesNotContain("Creator")
          .doesNotContain("ContextBudgetRequest")
          .doesNotContain("ResponseEntity")
          .doesNotContain("at com.")
          .doesNotContain("\tat ");
      assertThat(content)
          .as("%s: nor echo the caller's rejected value back to them", body.getKey())
          .doesNotContain(needle);
    }

    // All four land on the same handler with the same contract. A shape that drifted — a 500 for
    // the truncated body, say, or a 415 for the empty one — would mean one of these stopped
    // reaching the handler this test exists to cover, and the assertions above would go quiet
    // without going red.
    assertThat(shapes)
        .as("every unreadable body is one 400 MALFORMED_REQUEST, and all four reach that handler")
        .containsExactly(
            Map.entry("truncated json", "400 MALFORMED_REQUEST violations=0"),
            Map.entry("budget is a string", "400 MALFORMED_REQUEST violations=0"),
            Map.entry("budget.maxItems is a string", "400 MALFORMED_REQUEST violations=0"),
            Map.entry("no body at all", "400 MALFORMED_REQUEST violations=0"));

    assertThat(logLinesContaining(needle))
        .as("and the rejected value reached no log line either")
        .isEmpty();
    assertThat(packCount()).as("no unreadable body compiled anything").isEqualTo(before);
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
    assertThat(result.getResponse().getContentAsString().length())
        .as("the error body is a sentence, not a copy of the request")
        .isLessThan(10_000);
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
   * The in-file control, and the record of the out-of-file one.
   *
   * <p><b>The out-of-file control, re-measured.</b> A class asserting that one made-up URL 404s
   * cannot establish that the <em>other</em> tests are pointed at a live controller; only running
   * them against a dead route can. So the {@code url(...)} helper in this file and in
   * {@link ContextHttpSecretExposureTest} was repointed at
   * {@code /api/projects/{id}/context-DEAD-ROUTE} and both classes were run.
   *
   * <p><b>15 of 17 failed. Two survived, and both are accounted for:</b>
   *
   * <ul>
   *   <li>this test, and {@code ContextHttpSecretExposureTest#anUnmappedPathServesNothing} — each
   *       asserts a 404 from a path that is unmapped either way, so killing the real route cannot
   *       change its answer. That is the expected survivor and always was;
   *   <li>{@code ContextHttpSecretExposureTest#theCanonicalPayloadAndDigestAreClean}, which calls
   *       the assembler directly and never makes an HTTP request. Its own javadoc says it is "the
   *       one no HTTP route can show", so it surviving a dead HTTP route is the correct outcome and
   *       not a gap — but it was a survivor nobody had written down.
   * </ul>
   *
   * <p><b>Why the number in this file was wrong.</b> It said "13 of the 14 tests failed", with one
   * survivor. That measurement was correct when it was taken. Both classes then grew — this one by
   * two tests and the exposure class by one — and the sentence stayed. A count of tests written
   * into prose becomes a claim about a file that keeps changing, which is the ninth way this
   * project has manufactured a green and the reason the number above is dated to the run that
   * produced it rather than left as a standing fact. Re-measured at
   * {@code wave4/http-err-consolidation} with this file as it now stands.
   */
  @Test
  @DisplayName("Negative control: an unmapped sibling path serves none of these routes")
  void anUnmappedSiblingServesNothing() throws Exception {
    String dead = "/api/projects/" + aliceProject + "/context-surface-not-a-route";
    mvc.perform(get(dead).with(TestIdentity.as(alice))).andExpect(status().isNotFound());
    mvc.perform(get(dead).param("limit", "0").with(TestIdentity.as(alice)))
        .andExpect(status().isNotFound());
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

  /**
   * One error body swept for internal detail — over a body that exists.
   *
   * <p>The precondition is the point of the helper, not paperwork. Every assertion below is a
   * {@code doesNotContain}, and every {@code doesNotContain} passes over zero bytes. Review found
   * this being called twice on the 406, which has no body at all, under a comment claiming the 406
   * body was a place internal detail had been checked for. It had not been checked for anything.
   * A response that is meant to be empty goes to {@link #assertBodyIsAbsent} instead, where
   * emptiness is the assertion rather than the thing that makes the assertions meaningless.
   */
  private void assertBodyIsClean(String label, MvcResult result) throws Exception {
    String content = result.getResponse().getContentAsString();
    assertThat(content)
        .as("%s: a body swept for internal detail must have something in it to sweep", label)
        .isNotEmpty();
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
   * A response that is meant to carry no body, asserted as carrying none.
   *
   * <p>Where {@link #assertBodyIsClean} would be vacuous, this is the assertion that is not: the
   * guarantee for a 406 is not "its body contains no package name", it is "there is no body",
   * because there is no representation this caller would accept. A body appearing here would be a
   * body Spring could not write being written anyway.
   */
  private void assertBodyIsAbsent(String label, MvcResult result) throws Exception {
    assertThat(result.getResponse().getContentAsString())
        .as("%s: there is no representation this caller accepts, so there is nothing to send", label)
        .isEmpty();
  }

  /**
   * The same reduction as {@link #shapeOf}, widened to the one route in this class that answers a
   * success with an array rather than an error with an object.
   *
   * <p>It exists so that the {@code limit} matrix can assert <b>status and body</b> in one cell.
   * Status alone is what let CTX-09B-2 hide: {@code ?limit=} answered 200, and only the body said
   * that the page had a size the caller never named. A matrix that compared statuses would have
   * called that row correct.
   */
  private String listShapeOf(MvcResult result) throws Exception {
    String content = result.getResponse().getContentAsString();
    if (content.isEmpty()) {
      return result.getResponse().getStatus() + " <empty body>";
    }
    JsonNode body = json.readTree(content);
    if (body.isArray()) {
      return result.getResponse().getStatus() + " packs=" + body.size();
    }
    return shapeOf(result);
  }

  /** The one field two otherwise identical error bodies legitimately disagree on. */
  private static String normaliseTimestamp(String body) {
    return body.replaceAll("\"timestamp\":\"[^\"]+\"", "\"timestamp\":\"<t>\"");
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
