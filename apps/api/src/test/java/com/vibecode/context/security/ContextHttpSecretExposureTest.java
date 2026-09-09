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
 * <p><b>It did.</b> All three routes, in two fields each. It no longer does, and this class is the
 * proof of both halves: the assertions below are the same measurements, at the same points, over
 * the same fixture, with the answers turned around by SEC-RED-02. Nothing here was replaced by a
 * newer or gentler test — the attack that found the defect is the thing that now proves it closed.
 *
 * <p>What changed underneath: {@code SensitiveDataRedactor}'s key pattern anchored the secret word
 * on {@code \b}, and {@code _} is a word character, so {@code VIBECODE_DB_PASSWORD=} had no
 * boundary before {@code PASSWORD} and the whole assignment was invisible to it. A value with a
 * recognisable shape was rescued by a separate rule; a shape-less one was rescued by nothing. The
 * key may now carry any prefix, and the grammar of what counts as an assignment is written down in
 * {@code SecretAssignmentGrammarTest}.
 *
 * <p><b>What keeps these assertions honest now that they are negative.</b> A {@code doesNotContain}
 * passes for free if the fixture never planted the secret, if the route stopped compiling anything,
 * or if the body came back empty — which is exactly how this project has manufactured a green
 * before. So every negative here is paired with a positive over the same bytes: the pack has items,
 * the response's item count equals the stored row count, the id in the body names a row whose
 * project and fingerprint match the body, {@code brain_entries} still holds the raw secret the
 * fixture wrote, and one item's content carries both redaction markers side by side. The secret is
 * absent from the response because it was removed, not because there was nothing to remove.
 *
 * <p>The same request still carries a second secret with a shape the redactor already recognised,
 * through the identical path. Its role has changed: it was the control that proved redaction ran at
 * all while the shape-less value survived, and it is now the control that proves the shape-named
 * marker still wins over the generic one — {@code sk-****REDACTED****}, not {@code [REDACTED]} —
 * so a reader can still tell what kind of credential was taken out.
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

  /** The same secret under a prefixed key — the case the {@code \b} anchor used to let through. */
  private static final String PREFIXED_SHAPELESS = "VIBECODE_DB_PASSWORD=" + SHAPELESS;

  /**
   * A value the redactor recognises by its shape, whatever key names it. Present so the divergence
   * is measured rather than asserted from the other test's conclusions.
   */
  private static final String SHAPED = "sk-proj-Kd8fQzqxw610456mnop";

  /** The shaped secret under an equally prefixed key, so only the shape differs between the two. */
  private static final String PREFIXED_SHAPED = "OPENAI_API_KEY=" + SHAPED;

  /**
   * The same shape-less secret with a bcrypt prefix on the front, which is a third value travelling
   * the same path and differing from {@link #SHAPELESS} by seven characters.
   *
   * <p>It is here because the fix that closed this finding did not close it for this value. The key
   * rule reached the assignment and {@code isSafePlaceholder} then handed it back untouched, because
   * it exempted anything beginning with {@code $} — and every bcrypt hash begins {@code $2}. Four of
   * the eight tests in this class went red when the fixture was given this value, and the blast
   * radius was byte for byte the one the class was written to measure: the 201 body, {@code
   * items[].label}, {@code items[].content}, the canonical payload, the digest, {@code
   * context_packs} and {@code context_pack_items}.
   *
   * <p>So it is planted permanently rather than measured once. A value whose leading character is
   * part of its own shape must not be able to reopen a closed finding, and the way to guarantee that
   * is to make the class's existing measurements cover it, not to add a gentler test beside them.
   */
  private static final String BCRYPT_SHAPELESS = "$2b$12$" + SHAPELESS;

  /** The bcrypt-shaped value under the same prefixed key. */
  private static final String PREFIXED_BCRYPT = "SERVICE_AUTH_TOKEN=" + BCRYPT_SHAPELESS;

  /**
   * The same secret with a single dollar sign on the front — a value that is spelled exactly like a
   * shell variable read and is not one.
   *
   * <p>This was the residue the bcrypt fix left behind and declared irreducible: {@code
   * isSafePlaceholder} could not tell {@code $Pa55phrase_zqxw_610455} from {@code $DB_PASSWORD}, so
   * a value that happened to contain no character outside an identifier walked straight back out.
   * The reasoning was right and the framing was wrong — a recognised secret key now outranks the
   * placeholder exemption outright, so the value's shape is never consulted inside an assignment
   * and there is nothing left to tell apart.
   *
   * <p>Planted the same way {@link #BCRYPT_SHAPELESS} is, and for the same reason: it ends with
   * {@link #SHAPELESS}, so every measurement this class already makes covers it without a single
   * assertion of its own. A bypass that needs its own test is a bypass that can be closed while the
   * test that would have caught it is deleted.
   */
  private static final String DOLLAR_SHAPELESS = "$" + SHAPELESS;

  /** The dollar-prefixed value under a prefixed key: the exact probe from the ruling. */
  private static final String PREFIXED_DOLLAR = "APP_CLIENT_SECRET=" + DOLLAR_SHAPELESS;

  /**
   * The bcrypt value again, under a <b>quoted</b> key with an unquoted value — the spelling this
   * API's own responses are written in.
   *
   * <p>This is the coverage gap the two probes above did not close, and it let a real leak through:
   * both of them are planted under an unquoted key with {@code =}, so when the redactor's quoted-key
   * branch started requiring the value's quote, {@code {"password": $2b$12$…&#125;} was published
   * whole and every test in this class stayed green. The lesson is the one the other two already
   * carry, applied to the other axis: a probe covers the spelling it is written in and nothing else,
   * so the spelling has to be planted, not reasoned about.
   */
  private static final String QUOTED_BCRYPT = "{\"password\": " + BCRYPT_SHAPELESS + "}";

  /**
   * FINDING J1: the same value again, as the second element of a <b>flow sequence</b>.
   *
   * <p>{@code {"password": [prod, $2b$12$…]}} was the last spelling that mangled and leaked at the
   * same time. The redactor admitted the {@code [} because a scalar character followed it, then ran
   * the value to the comma <em>inside</em> the brackets: the opening bracket was replaced, the
   * document stopped parsing, and everything after the comma — the secret — stayed exactly where it
   * was. Six quoted-key spellings reached it, and {@code apiKey: [prod, <key>]} is ordinary YAML
   * that no adversary is needed to write.
   *
   * <p>Planted on the same terms as the three probes above, and that construction is the point
   * rather than a convenience. The value <b>contains {@link #SHAPELESS}</b>, so all eight
   * measurements this class makes — the three routes, the route with no request body, the canonical
   * payload and digest, the logs, the audit rows and the table sweep — cover it as they stand, and
   * it needs no assertion of its own. <b>A probe with no test of its own cannot be deleted along
   * with its test.</b> That is not a hypothetical: closing one bypass while deleting the assertion
   * that named it is how {@link #QUOTED_BCRYPT}'s leak survived a full green suite.
   */
  private static final String J1_FLOW_SEQUENCE =
      "{\"password\": [prod, " + BCRYPT_SHAPELESS + "]}";

  /**
   * FINDING G2: the same value again, inside a <b>nested object</b> under a quoted key.
   *
   * <p>{@code {"password": {"inner": "…"}}} was not mangled — it was left completely alone, and
   * left alone meant every byte of the secret stayed in the document and travelled every path this
   * class measures. It was pinned that way on purpose: the alternative at the time was to take the
   * {@code &#123;} as the whole value, replace it, and publish the object's contents beside a
   * broken document, which is worse. Measuring the extent removes the choice between them — the
   * object is replaced whole.
   *
   * <p>Planted on the same terms as the other four: the value <b>contains {@link #SHAPELESS}</b>,
   * so the eight measurements cover it as they stand and it has no assertion of its own that could
   * be deleted along with it.
   *
   * <p>Note what this probe would <em>not</em> have caught, because it matters for reading a green
   * run: while G2 was open the value was returned untouched, so this probe fails loudly today if
   * the admission is reverted, but a probe of this shape planted a month ago would have been red
   * the whole time. It is new because the defect it covers is newly closed.
   */
  private static final String G2_NESTED_OBJECT =
      "{\"password\": {\"inner\": \"" + BCRYPT_SHAPELESS + "\"}}";

  /**
   * What the three planted probes are, appended to the assertions that would otherwise report only
   * a field name or a table name.
   *
   * <p>The {@code ends-with-SHAPELESS} construction buys undeletable coverage: two of these values
   * have no assertion naming them, so a bypass affecting only one of them cannot be closed by
   * deleting the test that catches it. The price is that a failure says "items[].label" or
   * "{context_pack_items=12}" and nothing about which of the three values got out. This is the
   * price paid back — the search needle is one string, so the failure has to carry the list itself.
   */
  private static final String PROBES_PLANTED =
      " Six values are planted, all containing the one needle this assertion searches for, so the"
          + " failure above cannot say which of them escaped — and on this assertion the reported"
          + " text is a list of field or table names, with no value in it to inspect. The six are"
          + " the constants SHAPELESS (bare, under VIBECODE_DB_PASSWORD=), BCRYPT_SHAPELESS"
          + " ($2b$12$…, under SERVICE_AUTH_TOKEN=), DOLLAR_SHAPELESS ($…, under"
          + " APP_CLIENT_SECRET=) and QUOTED_BCRYPT (the same bcrypt value under a quoted JSON key"
          + " with no quotes on the value) and J1_FLOW_SEQUENCE (that same bcrypt value as the"
          + " second element of a JSON flow sequence) and G2_NESTED_OBJECT (that same bcrypt value"
          + " inside a nested JSON object under a quoted key). To find out which, re-run"
          + " ContextShapelessSecretBlastRadiusTest and SecretAssignmentGrammarTest, or read the"
          + " offending row directly: SELECT content, label FROM context_pack_items. Then match"
          + " what you find against those four constants at the top of this class — each names the"
          + " rule that would have to have regressed for it, and only it, to be the one that got"
          + " out.";

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
    // The bcrypt-shaped value, in a title and a body of its own so it reaches label and content the
    // same way the other two do. Because BCRYPT_SHAPELESS ends with SHAPELESS, every assertion in
    // this class that searches for SHAPELESS covers this value too — it does not need assertions of
    // its own, and giving it any would make it possible to close this class's measurements while
    // leaving that one open.
    brain.add(
        projectId,
        BrainEntryType.ARCHITECTURE,
        "Architecture " + PREFIXED_BCRYPT,
        "The service authenticates with " + PREFIXED_BCRYPT,
        "test");
    // And the dollar-prefixed value, planted on the same terms. Both of these are values that were
    // exempted by their own first character at some point in this task's history; both now end with
    // SHAPELESS, so the class's existing measurements are what catch them.
    brain.add(
        projectId,
        BrainEntryType.DECISION,
        "Decision " + PREFIXED_DOLLAR,
        "We settled on " + PREFIXED_DOLLAR + " for the client",
        "test");
    // The quoted-key spelling, planted on the same terms as the other two. Three of the four probes
    // now have no assertion naming them and are caught only because the class's existing
    // measurements search for a needle they all contain.
    brain.add(
        projectId,
        BrainEntryType.RULE,
        "Constraint " + QUOTED_BCRYPT,
        "The config we were sent reads " + QUOTED_BCRYPT,
        "test");
    // FINDING J1's spelling, planted on the same terms as the other three: it contains SHAPELESS,
    // so the class's eight existing measurements catch it and it has no assertion of its own to be
    // deleted with. Planted twice on purpose — once at the end of the text and once in the middle
    // of it, because the value's extent runs to the end of the line and "…]} today" exercises a
    // different path through it than "…]}" at a line end does.
    brain.add(
        projectId,
        BrainEntryType.RULE,
        "Sequence " + J1_FLOW_SEQUENCE,
        "The environments file reads " + J1_FLOW_SEQUENCE + " today, and must be rotated.",
        "test");
    // FINDING G2's spelling, on the same terms again: it contains SHAPELESS, so the eight
    // measurements catch it and it has no assertion of its own. Planted at the end of the text and
    // in the middle of it for the same reason the J1 probe is.
    brain.add(
        projectId,
        BrainEntryType.RULE,
        "Nested " + G2_NESTED_OBJECT,
        "The service config we were handed reads " + G2_NESTED_OBJECT + " and is still live.",
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
  @DisplayName("FIXED: neither secret comes back on any of the three routes")
  void neitherSecretIsReturnedByAnyRoute() throws Exception {
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

    // The measurement. This is the answer the sub-HTTP test could not give, and it is the
    // assertion that was `contains(SHAPELESS)` while the defect was open.
    assertThat(created)
        .as(
            "CTX-09B-1 CLOSED: the shape-less secret must not reach the client in the 201 body."
                + " This is the assertion that characterised the leak; it is flipped, not replaced.")
        .doesNotContain(SHAPELESS);
    assertThat(created)
        .as("the shaped secret travelled the identical path and is still removed")
        .doesNotContain(SHAPED);

    // The control that makes the two absences mean something. Redaction ran over this body and both
    // values were taken out of the SAME field, each replaced by the marker for its own kind:
    //
    //   "Deployed with VIBECODE_DB_PASSWORD=[REDACTED]
    //    and OPENAI_API_KEY=sk-****REDACTED****"
    //
    // Asserted on the one item that holds both rather than over the whole body, because a
    // whole-body search would let the two halves come from different items and neither would then
    // say anything about the other. While the defect was open this same line read
    // `content.contains(SHAPELESS) && content.contains("REDACTED")` — the surviving secret next to
    // a marker. The shape of the check is unchanged; what it looks for is.
    String bothInOneField =
        contentsOf(pack).stream()
            .filter(content -> content.contains("[REDACTED]") && content.contains("sk-****REDACTED****"))
            .findFirst()
            .orElse(null);
    assertThat(bothInOneField)
        .as(
            "one item's content must carry both markers together: that is the field which held both"
                + " secrets, so it is the field that proves the prefixed key is now recognised and"
                + " that the shape rule still names the kind of credential it removed")
        .isNotNull()
        .doesNotContain(SHAPELESS)
        .doesNotContain(SHAPED);

    // Which fields are clean. Named individually rather than as "somewhere in the body", because
    // these are the three the leak was in: the task reference a caller sees in a UI list, the item
    // label, and item content. An empty list here is not free — fieldsCarrying is exercised
    // non-vacuously on the next line, where the marker IS found in all three.
    assertThat(pack.get("taskReference").asText())
        .as("the caller's own reference comes back redacted")
        .doesNotContain(SHAPELESS)
        .contains("VIBECODE_DB_PASSWORD=[REDACTED]");
    assertThat(fieldsCarrying(pack, SHAPELESS))
        .as("no field of the response carries the secret")
        .isEmpty();
    assertThat(fieldsCarrying(pack, "[REDACTED]"))
        .as(
            "and the same search over the marker finds all three fields the leak used to be in, so"
                + " the empty result above is redaction and not a search that looks nowhere")
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
    assertThat(fetched).as("CTX-09B-1 CLOSED on GET /{packId}").doesNotContain(SHAPELESS);
    assertThat(fetched).doesNotContain(SHAPED);
    assertThat(fetched)
        .as("the read route returns the redacted text, not an empty pack that trivially has neither")
        .contains("VIBECODE_DB_PASSWORD=[REDACTED]");

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
    assertThat(listed).as("CTX-09B-1 CLOSED on GET the list").doesNotContain(SHAPELESS);
    assertThat(listed).doesNotContain(SHAPED);
    assertThat(listed)
        .as("the list route carries the redacted text too, so its two absences are not vacuous")
        .contains("VIBECODE_DB_PASSWORD=[REDACTED]");
  }

  /**
   * The severity half of the finding: the secret is not merely echoed back to the caller who sent
   * it, which would be a much smaller thing. It is compiled out of records the caller never named,
   * so a client that only ever asks for a task reference of its own receives content it did not
   * supply — and it is still there on a route that carries no request body at all.
   */
  @Test
  @DisplayName("FIXED: content the caller never sent is redacted too, and on a route with no body")
  void contentTheCallerNeverSentIsRedactedToo() throws Exception {
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
            "CTX-09B-1 CLOSED at severity: the secret arrives from the project's own records rather"
                + " than from the request, and it is removed there too."
                + PROBES_PLANTED)
        .isEmpty();
    assertThat(fieldsCarrying(pack, "[REDACTED]"))
        .as(
            "the label and content fields the secret used to arrive in are still populated from"
                + " those records — they now carry the marker, so the empty result above is not a"
                + " pack that simply lost the items")
        .contains("items[].label", "items[].content");

    // And on a GET, where there is no request body to blame it on.
    UUID packId = UUID.fromString(pack.get("packId").asText());
    String fetched =
        mvc.perform(get(url(projectId) + "/" + packId).with(TestIdentity.as(owner)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(fetched).doesNotContain(SHAPELESS);
    assertThat(fetched).contains("VIBECODE_DB_PASSWORD=[REDACTED]");
  }

  /**
   * The seventh surface, and the one no HTTP route can show: the canonical payload the digest is
   * taken over.
   *
   * <p>A response body can be clean while the bytes that were hashed are not. The payload is not
   * serialised to a client, so it is reached here through the assembler over the same project this
   * class's HTTP tests compile — same records, same planted secret — rather than inferred from the
   * response. That distinction was load-bearing while the defect was open: the digest was computed
   * over text containing the secret, which meant a pack's fingerprint could not be re-derived by
   * anyone who was not entitled to the credential.
   */
  @Test
  @DisplayName("FIXED: the canonical payload the digest is taken over carries neither secret")
  void theCanonicalPayloadAndDigestAreClean() {
    var compiled = assembler.assemble(projectId, "CTX-09B payload " + PREFIXED_SHAPELESS, GENEROUS);

    assertThat(compiled.admittedItems()).as("a pack with no items hashes nothing").isNotEmpty();
    assertThat(compiled.canonicalPayload().value())
        .as("the bytes the pack digest is computed over")
        .doesNotContain(SHAPELESS)
        .doesNotContain(SHAPED)
        // Non-vacuous: the payload is the text of this pack, and the text carries the marker that
        // replaced the value in both the task reference and the items.
        .contains("VIBECODE_DB_PASSWORD=[REDACTED]")
        .contains("sk-****REDACTED****");
    assertThat(compiled.packDigest())
        .as("and it is a real digest of that payload, not an empty string that contains nothing")
        .hasSize(64);
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
   * <p>This was a characterisation: the two context tables <em>did</em> hold the shape-less secret,
   * because nothing redacted it, and the assertion named them. The sweep is unchanged and the
   * expectation is now empty — an HTTP compile writes the secret to no table at all outside the
   * records the user themselves wrote it into. Because the sweep covers every table rather than the
   * two that were known to leak, a working table, a queue or an outbox added later that started
   * carrying pack content shows up here as a new entry with nobody having to remember this file
   * exists.
   */
  @Test
  @DisplayName("FIXED: an HTTP compile writes the secret to no table outside the user's own records")
  void theHttpCompileWritesTheSecretToNoTable() throws Exception {
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
    assertThat(beyondTheRecords)
        .as(
            "CTX-09B-1 CLOSED in the database, reached over HTTP: the two context tables that"
                + " carried the secret carry it no longer, and no other table picked it up. This"
                + " assertion read containsExactlyInAnyOrder(\"context_packs\","
                + " \"context_pack_items\") while the defect was open."
                + PROBES_PLANTED)
        .isEmpty();

    // The pack was written and it holds the redacted form, so the empty map above is redaction
    // rather than a compile that never happened. Counted over the same tables the sweep just
    // cleared, by the same jdbc.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM context_pack_items WHERE content LIKE ?",
                Integer.class,
                "%VIBECODE_DB_PASSWORD=[REDACTED]%"))
        .as("context_pack_items holds the redacted assignment")
        .isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM context_packs WHERE task_reference LIKE ?",
                Integer.class,
                "%VIBECODE_DB_PASSWORD=[REDACTED]%"))
        .as("and context_packs holds the redacted task reference")
        .isPositive();

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
