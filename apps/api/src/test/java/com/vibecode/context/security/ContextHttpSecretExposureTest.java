package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.identity.domain.User;
import com.vibecode.output.domain.EvidenceType;
import com.vibecode.project.domain.Project;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.shared.logging.LogCapture;
import com.vibecode.support.TestIdentity;
import com.vibecode.support.logging.LoggerLevelIsolation;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * <b>The question this class exists to answer.</b> {@link ContextShapelessSecretBlastRadiusTest}
 * established, below HTTP, that a secret the Guardian's redactor does not recognise reaches item
 * content, the item label, the canonical payload, the digest and three database columns. It could
 * not answer the one thing that decides how bad the defect is, because it never called a
 * controller: <b>does that secret come back to a client in an HTTP response body?</b>
 *
 * <p>It does. All three routes, in two fields each. That is measured here rather than argued, and
 * the same request carries a second secret with a shape the redactor <em>does</em> recognise
 * through the identical path, so the divergence is visible in one response body: one value is
 * {@code [REDACTED]} on the wire and the other is not. Everything else about the two is the same —
 * same records, same prefixed key, same request, same route.
 *
 * <p><b>This is a characterisation test and it is meant to go red.</b> When {@code
 * SensitiveDataRedactor}'s {@code \b} anchor is fixed, the assertions that pin the exposure will
 * fail, and whoever fixes it is told by the failure to come here and rewrite the class rather than
 * to discover months later that a measured leak quietly stopped being measured. The assertions are
 * written as {@code contains} on purpose: an {@code isNotEmpty} on some list would survive the fix
 * and keep passing while measuring nothing.
 *
 * <h2>What is asserted alongside the leak, and why</h2>
 *
 * <p>Every representative request asserts three things together — the status, the schema of the
 * body, and the row the call left behind. A 201 whose body nobody reads proves that something
 * answered; it does not prove the route compiled a pack. The persisted check is a JDBC read of
 * {@code context_packs} by the id the response reported, so the response and the database have to
 * agree about the same pack for the test to pass. A mock standing in for the route could satisfy
 * the status; it could not satisfy the row.
 *
 * <p><b>The rejection paths are exercised with the secret in the request, not only the success
 * path.</b> That split is the whole reason this file is longer than it looks like it needs to be.
 * A leak that only appears when input is refused is the shape this project has already been bitten
 * by: nine leaking log categories were found only when someone finally sent invalid input, and one
 * of them printed a password on a validation failure. So the secret goes in over a body that is too
 * long, a body that is not JSON, a body whose budget is refused, a request with no CSRF token, and
 * a request from a user who may not see the project — and every resulting error body and every
 * captured log line is searched for it.
 *
 * <p>Every value here is synthetic and none resembles a credential that exists. No provider is
 * called, no vault is touched, and the database is created and dropped by the test run.
 */
@AutoConfigureMockMvc
@ExtendWith(LoggerLevelIsolation.class)
class ContextHttpSecretExposureTest extends ContextProbeFixture {

  /**
   * A secret with no shape any of the redactor's five value patterns recognises: not {@code sk-},
   * not {@code ghp_}, not a PEM block, not a database URL. A password, in other words — the most
   * ordinary secret there is.
   *
   * <p>The {@code zqxw} run makes it un-producible by a UUID at any length, so a hit in a
   * whole-schema sweep or a whole-body search is the value and never a collision. This project has
   * already manufactured one green with a four-character needle that a random UUID reproduced.
   */
  private static final String SHAPELESS = "Pa55phrase_zqxw_610455";

  /** The same secret under a prefixed key — the case the {@code \b} anchor lets through. */
  private static final String PREFIXED_SHAPELESS = "VIBECODE_DB_PASSWORD=" + SHAPELESS;

  /**
   * A value the redactor recognises by its shape, whatever key names it. Present so the divergence
   * is measured rather than asserted from the other test's conclusions.
   */
  private static final String SHAPED = "sk-proj-Kd8fQzqxw610456mnop";

  /** The shaped secret under an equally prefixed key, so only the shape differs between the two. */
  private static final String PREFIXED_SHAPED = "OPENAI_API_KEY=" + SHAPED;

  /**
   * The tables that are the project's own records: the user wrote the secret into these and the
   * engine only reads them. Every other table is in the "must be clean" half by default, so a table
   * added later that starts holding pack content fails the sweep without anyone updating a list.
   */
  private static final Set<String> USER_RECORD_TABLES =
      Set.of(
          "projects",
          "brain_entries",
          "roadmaps",
          "roadmap_phases",
          "tasks",
          "task_acceptance_criteria",
          "task_evidence",
          "output_analysis_records",
          "memory_update_proposals",
          "security_findings");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private User owner;
  private User outsider;
  private UUID projectId;
  private Logger root;
  private ListAppender<ILoggingEvent> captured;

  /**
   * A project carrying both secrets in every source the engine reads, planted here rather than by
   * {@link ContextProbeFixture#plantTheProbeEverywhere} on purpose: that method plants a
   * <em>bare</em> {@code TOKEN=} key, which the redactor matches, because the tests it serves are
   * about the engine's redaction boundary. This class needs the key the redactor misses.
   */
  @BeforeEach
  void plantBothSecretsAndListen() {
    owner = identity.createAndAuthenticate("http-exposure-owner");
    Project project =
        projects.create(
            "HTTP exposure project",
            "Deployed with " + PREFIXED_SHAPELESS + " and " + PREFIXED_SHAPED,
            "Guide a developer, configured with " + PREFIXED_SHAPELESS);
    projectId = project.getId();

    RoadmapPhase phase =
        roadmaps.addPhase(projectId, 1, "Foundations", "Set up using " + PREFIXED_SHAPELESS);
    Task task =
        tasks.addTask(
            projectId,
            phase.getId(),
            1,
            "Wire the deployment",
            "Export " + PREFIXED_SHAPELESS + " and " + PREFIXED_SHAPED + " before running.",
            RiskLevel.LOW);
    tasks.addCriterion(
        projectId, task.getId(), "The pipeline connects using " + PREFIXED_SHAPELESS, true);
    evidence.record(
        projectId,
        task.getId(),
        EvidenceType.BUILD_RESULT,
        "BUILD FAILURE\nauth rejected for " + PREFIXED_SHAPELESS,
        "maven");

    // Both secrets in the title as well as the body: the title becomes the item label and the body
    // becomes the item content, and they are two separate fields on the wire. A test that only
    // searched content would miss a label leak entirely.
    brain.add(
        projectId,
        BrainEntryType.TECHNOLOGY,
        "Technology " + PREFIXED_SHAPELESS + " and " + PREFIXED_SHAPED,
        "We deploy with " + PREFIXED_SHAPELESS + " and call out with " + PREFIXED_SHAPED,
        "test");
    brain.add(
        projectId,
        BrainEntryType.VISION,
        "Vision " + PREFIXED_SHAPELESS,
        "Written next to " + PREFIXED_SHAPELESS,
        "test");

    outsider = identity.createUser("http-exposure-outsider");
    identity.authenticateAs(owner);

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

  // ------------------------------------------------------------------ the headline measurement

  @Test
  @DisplayName("FINDING: the shape-less secret comes back raw on all three routes; the shaped one does not")
  void theShapelessSecretIsReturnedByEveryRoute() throws Exception {
    // Compile. Status, schema and the row it left behind, asserted together.
    String created =
        mvc.perform(compile(owner, projectId, body("CTX-09B exposure " + PREFIXED_SHAPELESS)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.packId").isNotEmpty())
            .andExpect(jsonPath("$.projectId").value(projectId.toString()))
            .andExpect(jsonPath("$.contentFingerprint").isNotEmpty())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.usage.items").isNumber())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode pack = json.readTree(created);
    UUID packId = UUID.fromString(pack.get("packId").asText());
    assertThat(pack.get("items").size())
        .as("a pack with no items cannot leak an item's content")
        .isPositive();

    // Proof of a real route: the id in the body names a row, and that row agrees with the body
    // about the project and the fingerprint. Nothing standing in for the controller could do this.
    String storedProject =
        jdbc.queryForObject(
            "SELECT CAST(project_id AS VARCHAR) FROM context_packs WHERE id = ?",
            String.class,
            packId);
    assertThat(storedProject).isEqualTo(projectId.toString());
    String storedFingerprint =
        jdbc.queryForObject(
            "SELECT content_fingerprint FROM context_packs WHERE id = ?", String.class, packId);
    assertThat(storedFingerprint).isEqualTo(pack.get("contentFingerprint").asText());
    Integer storedItems =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ?", Integer.class, packId);
    assertThat(storedItems)
        .as("the response's item array and the stored rows are the same pack")
        .isEqualTo(pack.get("items").size());

    // The measurement. This is the answer the sub-HTTP test could not give.
    assertThat(created)
        .as(
            "FINDING CTX-09B-1: the shape-less secret reaches the client verbatim in the 201 body."
                + " This assertion is expected to FAIL when SensitiveDataRedactor's \\b anchor is"
                + " fixed; when it does, rewrite this class rather than deleting it.")
        .contains(SHAPELESS);
    assertThat(created)
        .as("the shaped secret travelled the identical path and was removed by its shape alone")
        .doesNotContain(SHAPED);
    // The control that makes the leak legible: redaction *did* run over this body. The shaped
    // secret came back as sk-****REDACTED**** from the very same item, so the two values differ in
    // outcome and in nothing else. One item's content carries both, side by side:
    //
    //   "Deployed with VIBECODE_DB_PASSWORD=Pa55phrase_zqxw_610455
    //    and OPENAI_API_KEY=sk-****REDACTED****"
    //
    // That single line is the finding. It is asserted rather than quoted, on the item that holds
    // both, because a whole-body search would let the two halves come from different items and the
    // "redaction ran" half would then prove nothing about the value that survived.
    String bothInOneField =
        contentsOf(pack).stream()
            .filter(content -> content.contains(SHAPELESS) && content.contains("REDACTED"))
            .findFirst()
            .orElse(null);
    assertThat(bothInOneField)
        .as(
            "one item's content must carry the surviving secret and a redaction marker together;"
                + " without that, 'the shaped one was removed' and 'the shape-less one was not' are"
                + " two claims about two different items")
        .isNotNull();

    // Which fields carry it. "Somewhere in the body" would understate a leak that is in the task
    // reference the caller can see in a UI list, in an item label, and in item content.
    assertThat(pack.get("taskReference").asText())
        .as("the caller's own reference is echoed unredacted")
        .contains(SHAPELESS);
    assertThat(fieldsCarrying(pack, SHAPELESS))
        .as("the leak is not confined to the field the caller supplied")
        .contains("taskReference", "items[].label", "items[].content");

    // Route two: read it back by id. Same three assertions, same measurement.
    String fetched =
        mvc.perform(get(url(projectId) + "/" + packId).with(TestIdentity.as(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.packId").value(packId.toString()))
            .andExpect(jsonPath("$.items").isArray())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(fetched).get("items").size())
        .as("the read route rebuilt the same pack from its rows")
        .isEqualTo(pack.get("items").size());
    assertThat(fetched).as("FINDING CTX-09B-1 on GET /{packId}").contains(SHAPELESS);
    assertThat(fetched).doesNotContain(SHAPED);

    // Route three: the list. Membership, not order — the clock is frozen suite-wide and every pack
    // shares an assembledAt, so asserting a position here would be asserting the tie-break rather
    // than anything this test is about. Ordering is pinned elsewhere, by a test that moves the
    // clock on purpose.
    String listed =
        mvc.perform(get(url(projectId)).with(TestIdentity.as(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> ids = new ArrayList<>();
    json.readTree(listed).forEach(node -> ids.add(node.get("packId").asText()));
    assertThat(ids).as("the list route read the pack the compile route wrote").contains(packId.toString());
    assertThat(listed).as("FINDING CTX-09B-1 on GET the list").contains(SHAPELESS);
    assertThat(listed).doesNotContain(SHAPED);
  }

  /**
   * The severity half of the finding: the secret is not merely echoed back to the caller who sent
   * it, which would be a much smaller thing. It is compiled out of records the caller never named,
   * so a client that only ever asks for a task reference of its own receives content it did not
   * supply — and it is still there on a route that carries no request body at all.
   */
  @Test
  @DisplayName("FINDING: the leak is content the caller never sent, not an echo of their own request")
  void theLeakIsNotAnEchoOfTheRequest() throws Exception {
    String created =
        mvc.perform(compile(owner, projectId, body("CTX-09B no secret in this request")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode pack = json.readTree(created);
    assertThat(pack.get("taskReference").asText())
        .as("the request carried no secret at all")
        .doesNotContain(SHAPELESS);
    assertThat(fieldsCarrying(pack, SHAPELESS))
        .as(
            "FINDING CTX-09B-1, severity: the secret arrives from the project's own records, so no"
                + " care taken by the client prevents it")
        .contains("items[].label", "items[].content");

    // And on a GET, where there is no request body to blame it on.
    UUID packId = UUID.fromString(pack.get("packId").asText());
    String fetched =
        mvc.perform(get(url(projectId) + "/" + packId).with(TestIdentity.as(owner)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(fetched).contains(SHAPELESS);
  }

  // ------------------------------------------------------------------ rejection paths

  /**
   * Five ways to be refused, each carrying the secret in the request, each checked for it in the
   * response.
   *
   * <p>The error bodies are clean, and the reason is worth stating because it is not a redaction:
   * none of these handlers echoes the rejected value at all. {@code MALFORMED_REQUEST} and
   * {@code VALIDATION_ERROR} report a field and a rule, the 404 reports an id, and the CSRF refusal
   * has no body to speak of. Cleanliness by construction survives a redactor defect, which is
   * exactly why it is worth having measured against a value redaction is known to miss.
   */
  @Test
  @DisplayName("No rejection path echoes the secret it refused, and none of them stores a pack")
  void rejectionPathsDoNotEchoTheSecret() throws Exception {
    int packsBefore = packCount();

    Map<String, String> refusals = new LinkedHashMap<>();

    // Too long for the DTO's cap, with the secret inside it.
    String oversized = "O".repeat(600) + " " + PREFIXED_SHAPELESS;
    refusals.put(
        "oversized taskReference",
        mvc.perform(compile(owner, projectId, body(oversized)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andReturn()
            .getResponse()
            .getContentAsString());

    // Blank reference; the secret rides in a field the DTO does not declare, which Jackson must
    // neither bind nor repeat back.
    refusals.put(
        "blank taskReference with an unknown field",
        mvc.perform(
                compile(
                    owner,
                    projectId,
                    "{\"taskReference\":\"\",\"note\":\"" + PREFIXED_SHAPELESS + "\"}"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString());

    // Not JSON at all.
    refusals.put(
        "malformed JSON",
        mvc.perform(compile(owner, projectId, "{\"taskReference\": \"" + PREFIXED_SHAPELESS + "\""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
            .andReturn()
            .getResponse()
            .getContentAsString());

    // A budget the DTO refuses, with the secret in the reference beside it.
    refusals.put(
        "invalid budget",
        mvc.perform(
                compile(
                    owner,
                    projectId,
                    "{\"taskReference\":\""
                        + PREFIXED_SHAPELESS
                        + "\",\"budget\":{\"maxItems\":0}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andReturn()
            .getResponse()
            .getContentAsString());

    // No CSRF token.
    refusals.put(
        "missing CSRF token",
        mvc.perform(
                post(url(projectId) + "/compile")
                    .with(TestIdentity.as(owner))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(PREFIXED_SHAPELESS)))
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse()
            .getContentAsString());

    // A user who may not see the project, sending the secret at it.
    refusals.put(
        "another user's project",
        mvc.perform(compile(outsider, projectId, body("CTX-09B outsider " + PREFIXED_SHAPELESS)))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString());

    refusals.forEach(
        (path, responseBody) -> {
          assertThat(responseBody).as("%s echoed the secret", path).doesNotContain(SHAPELESS);
          assertThat(responseBody).as("%s echoed the shaped secret", path).doesNotContain(SHAPED);
          assertThat(responseBody)
              .as("%s leaked an internal detail", path)
              .doesNotContain("org.hibernate")
              .doesNotContain("java.lang")
              .doesNotContain("com.vibecode")
              .doesNotContain("SELECT ")
              .doesNotContain("at com.");
        });

    assertThat(packCount())
        .as("not one of six refusals left a pack behind")
        .isEqualTo(packsBefore);
  }

  // ------------------------------------------------------------------ logs, both paths

  /**
   * Every line the application emitted while the six refusals and one success above ran, read
   * through {@link LogCapture#lineOf} so the message, every cause and every suppressed throwable
   * are searched — not {@code String.valueOf(getThrowableProxy())}, which renders
   * {@code ThrowableProxy@1f69937a} and has already shipped a green that measured nothing.
   */
  @Test
  @DisplayName("Neither the served request nor any of the six refusals logs the secret")
  void neitherSuccessNorRefusalLogsTheSecret() throws Exception {
    mvc.perform(compile(owner, projectId, body("CTX-09B log success " + PREFIXED_SHAPELESS)))
        .andExpect(status().isCreated());
    mvc.perform(compile(owner, projectId, "{\"taskReference\":\"\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(compile(owner, projectId, "{\"taskReference\": \"" + PREFIXED_SHAPELESS))
        .andExpect(status().isBadRequest());
    mvc.perform(compile(outsider, projectId, body("CTX-09B log outsider " + PREFIXED_SHAPELESS)))
        .andExpect(status().isNotFound());
    mvc.perform(get(url(projectId) + "/" + UUID.randomUUID()).with(TestIdentity.as(owner)))
        .andExpect(status().isNotFound());

    assertThat(logLinesContaining(SHAPELESS))
        .as("a log line carrying the shape-less secret")
        .isEmpty();
    assertThat(logLinesContaining(SHAPED)).as("a log line carrying the shaped secret").isEmpty();
    assertThat(logLinesContaining("HTTP exposure project"))
        .as("a refusal names an id; it does not describe the project it refused to open")
        .isEmpty();

    // The non-vacuity control, and exactly what satisfies it. An `isNotEmpty` on the whole capture
    // would be satisfied by Hibernate and Spring alone — the context module declares no logger at
    // all — and would prove only that the appender was attached. This names a line a com.vibecode
    // logger writes on the refusal path, so it establishes that the application's own logging ran
    // inside the window and was searched.
    assertThat(logLinesContaining("ACCESS_DENIED"))
        .as("the cross-user refusal is logged by the access gate, which makes this capture live")
        .isNotEmpty();
  }

  // ------------------------------------------------------------------ audit and database

  @Test
  @DisplayName("No audit row carries either secret, on the served path or the refused one")
  void auditKeepsNeitherSecret() throws Exception {
    mvc.perform(compile(owner, projectId, body("CTX-09B audit " + PREFIXED_SHAPELESS)))
        .andExpect(status().isCreated());
    mvc.perform(compile(outsider, projectId, body("CTX-09B audit outsider " + PREFIXED_SHAPELESS)))
        .andExpect(status().isNotFound());

    List<Map<String, Object>> events = jdbc.queryForList("SELECT * FROM audit_events");
    assertThat(events).as("no audit rows at all would make the emptiness below free").isNotEmpty();

    List<Map<String, Object>> denials =
        events.stream()
            .filter(event -> "CROSS_USER_ACCESS_DENIED".equals(String.valueOf(event.get("event_type"))))
            .toList();
    assertThat(denials)
        .as("the refusal must have been audited through HTTP, or the sweep misses the refusal path")
        .isNotEmpty();

    for (Map<String, Object> event : events) {
      String row = stringify(event);
      assertThat(row).as("audit row carrying the shape-less secret").doesNotContain(SHAPELESS);
      assertThat(row).as("audit row carrying the shaped secret").doesNotContain(SHAPED);
      assertThat(row)
          .as("audit row describing the project rather than naming it")
          .doesNotContain("HTTP exposure project");
    }
  }

  /**
   * Where an HTTP compile puts the secret in the database, swept over the whole schema rather than
   * over the two tables it is expected to reach.
   *
   * <p>This is a characterisation and the {@code contains} below is deliberate: the two context
   * tables <em>do</em> hold the shape-less secret today, because nothing redacted it. What the test
   * pins is that the exposure is exactly those two tables and no third one — a working table, a
   * queue, an outbox added later that started carrying pack content would show up here as a new
   * entry with nobody having to remember this file exists.
   */
  @Test
  @DisplayName("FINDING: an HTTP compile writes the secret to the two context tables and no others")
  void theHttpCompileWritesTheSecretToExactlyTwoTables() throws Exception {
    mvc.perform(compile(owner, projectId, body("CTX-09B schema " + PREFIXED_SHAPELESS)))
        .andExpect(status().isCreated());

    Map<String, Integer> shapeless = occurrencesPerTable(SHAPELESS);
    Map<String, Integer> shaped = occurrencesPerTable(SHAPED);

    assertThat(shapeless.get("brain_entries"))
        .as("the secret must still be in the records it was written to, or every zero is free")
        .isPositive();

    Map<String, Integer> beyondTheRecords = new TreeMap<>();
    shapeless.forEach(
        (table, count) -> {
          if (count > 0 && !USER_RECORD_TABLES.contains(table)) {
            beyondTheRecords.put(table, count);
          }
        });
    assertThat(beyondTheRecords.keySet())
        .as(
            "FINDING CTX-09B-1 in the database, reached over HTTP: the two context tables carry the"
                + " secret because redaction did not recognise it. Expected to shrink to empty when"
                + " the redactor is fixed.")
        .containsExactlyInAnyOrder("context_packs", "context_pack_items");

    Map<String, Integer> shapedBeyondTheRecords = new TreeMap<>();
    shaped.forEach(
        (table, count) -> {
          if (count > 0 && !USER_RECORD_TABLES.contains(table)) {
            shapedBeyondTheRecords.put(table, count);
          }
        });
    assertThat(shapedBeyondTheRecords)
        .as("the shaped secret went through the same route and reached no context table at all")
        .isEmpty();
  }

  // ------------------------------------------------------------------ negative control

  /**
   * The control for everything above: the same shape of request against a path that is not mapped.
   *
   * <p>It is here so the file carries its own evidence that its assertions bite, but it is not the
   * whole of the negative control this work was asked for. A test class asserting that one made-up
   * URL 404s cannot establish that the <em>other</em> tests are pointed at a live controller; only
   * running them against a dead route can. So that was done: the {@code url(...)} helper in this
   * file and in {@link ContextHttpErrorSurfaceTest} was rewritten to
   * {@code /api/projects/{id}/context-DEAD-ROUTE} and both classes were run.
   *
   * <p><b>13 of the 14 tests failed.</b> The single survivor was this one, which is the expected and
   * only acceptable survivor: it asserts a 404 from a path that is unmapped either way, so killing
   * the real route cannot change its answer. Every other test in both classes — the leak
   * measurements, the census, the method surface, the audit and schema sweeps, the log probes — went
   * red, which is what establishes that they are reading a real controller and not their own
   * scaffolding. The helper was then restored and both classes returned to green.
   */
  @Test
  @DisplayName("Negative control: the same requests against an unmapped path are 404 with no pack")
  void anUnmappedPathServesNothing() throws Exception {
    int before = packCount();
    String dead = "/api/projects/" + projectId + "/context-not-a-route";

    mvc.perform(
            post(dead + "/compile")
                .with(TestIdentity.as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("CTX-09B dead route")))
        .andExpect(status().isNotFound());
    mvc.perform(get(dead).with(TestIdentity.as(owner))).andExpect(status().isNotFound());

    assertThat(packCount()).as("an unmapped path compiles nothing").isEqualTo(before);
  }

  // ------------------------------------------------------------------ helpers

  private static String url(UUID project) {
    return "/api/projects/" + project + "/context";
  }

  private static String body(String taskReference) {
    return "{\"taskReference\":\"" + taskReference + "\"}";
  }

  private MockHttpServletRequestBuilder compile(User caller, UUID project, String requestBody) {
    return post(url(project) + "/compile")
        .with(TestIdentity.as(caller))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestBody);
  }

  private int packCount() {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM context_packs", Integer.class);
    return count == null ? 0 : count;
  }

  /**
   * Which named fields of a pack response carry the needle, so a failure says <em>where</em> the
   * leak is rather than only that the body contains a string somewhere.
   */
  private static List<String> fieldsCarrying(JsonNode pack, String needle) {
    List<String> fields = new ArrayList<>();
    if (pack.get("taskReference").asText().contains(needle)) {
      fields.add("taskReference");
    }
    for (JsonNode item : pack.get("items")) {
      if (item.get("label").asText().contains(needle) && !fields.contains("items[].label")) {
        fields.add("items[].label");
      }
      if (item.get("content").asText().contains(needle) && !fields.contains("items[].content")) {
        fields.add("items[].content");
      }
    }
    return fields;
  }

  /** Every item's content, in the order the response listed them. */
  private static List<String> contentsOf(JsonNode pack) {
    List<String> contents = new ArrayList<>();
    pack.get("items").forEach(item -> contents.add(item.get("content").asText()));
    return contents;
  }

  /** Captured lines carrying the needle, rendered with the category that wrote them. */
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

  /**
   * Every table in the schema mapped to how many of its column values contain the needle.
   *
   * <p>Binary columns are stringified as {@code [B@...} and can never match; the three that exist
   * are the vault's ciphertext columns, which the context engine cannot write to. That gap is the
   * same one {@link ContextSchemaWideLeakTest} states, and it is restated rather than assumed.
   */
  private Map<String, Integer> occurrencesPerTable(String needle) {
    List<String> tables =
        jdbc.queryForList(
            "SELECT LOWER(table_name) FROM information_schema.tables"
                + " WHERE UPPER(table_schema) = 'PUBLIC' AND UPPER(table_type) = 'BASE TABLE'"
                + " ORDER BY 1",
            String.class);
    assertThat(tables)
        .as("the sweep must actually see the schema")
        .contains("context_packs", "context_pack_items", "audit_events", "brain_entries");

    Map<String, Integer> perTable = new LinkedHashMap<>();
    for (String table : tables) {
      List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM \"" + table + "\"");
      int occurrences = 0;
      for (Map<String, Object> row : rows) {
        for (Object value : row.values()) {
          if (value != null && String.valueOf(value).contains(needle)) {
            occurrences++;
          }
        }
      }
      perTable.put(table, occurrences);
    }
    return perTable;
  }

  private static String stringify(Map<String, Object> row) {
    StringBuilder text = new StringBuilder();
    row.values().forEach(value -> text.append(value).append(" | "));
    return text.toString();
  }
}
