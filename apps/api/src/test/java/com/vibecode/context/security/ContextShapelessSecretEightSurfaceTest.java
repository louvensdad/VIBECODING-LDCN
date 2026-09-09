package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextDigest;
import com.vibecode.guardian.domain.SensitiveDataRedactor;
import com.vibecode.identity.domain.User;
import com.vibecode.output.domain.EvidenceType;
import com.vibecode.project.domain.Project;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.shared.logging.LogCapture;
import com.vibecode.support.TestIdentity;
import com.vibecode.support.logging.LoggerLevelIsolation;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import java.time.Instant;
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

/**
 * <b>FINDING CTX-09B-1, re-exercised end to end: a shape-less secret under a prefixed key, measured
 * separately on all eight surfaces it could reach.</b>
 *
 * <p>The finding, in one sentence: {@code SensitiveDataRedactor}'s key/value rule was anchored on
 * {@code \b} and {@code _} is a word character, so there was no boundary between a prefix and the
 * key name — {@code SECRET=} matched, {@code DATABASE_PASSWORD=} did not. The redactor's other
 * rules match on the <em>shape</em> of a value, so an {@code sk-} key and a {@code ghp_} token were
 * removed whatever named them. The exposed case was therefore narrow and nasty:
 * <b>a credential with a recognisable shape was protected by its shape, and a credential with no
 * shape under a prefixed key was protected by nothing.</b> A password, a base64 master key or an
 * internal token is exactly that case. SEC-RED-02 closed it in {@code guardian/**}.
 *
 * <h2>Why this class exists next to {@link ContextShapelessSecretBlastRadiusTest}</h2>
 *
 * <p>That class measures the finding where it was found — through the assembler, on the pack's own
 * five in-process surfaces. It never makes an HTTP request. The finding as originally written up
 * was about what a caller can retrieve, and three of the eight surfaces named in it are responses:
 * the compile response, the single-pack read and the list. A measurement that stops at the
 * assembler cannot speak for the serialiser, the DTO mapping or the controller, and those are the
 * three layers between a redacted pack and a caller's screen.
 *
 * <p>So this class re-runs the finding over the whole route, one surface at a time, and reports
 * them as a map rather than as a conjunction. Eight assertions collapsed into one {@code isTrue()}
 * would name no surface when they failed; a map names the one that came back.
 *
 * <h2>The eight surfaces, and what each one is</h2>
 *
 * <ol>
 *   <li><b>database</b> — every column of every table in the schema, swept, minus the source
 *       records the user themselves wrote. The engine reads those; it does not get to copy them.
 *   <li><b>HTTP compile response</b> — the 201 body from {@code POST .../context/compile}.
 *   <li><b>HTTP GET</b> — the 200 body from {@code GET .../context/{packId}}.
 *   <li><b>HTTP list</b> — the 200 body from {@code GET .../context}.
 *   <li><b>audit</b> — every audit event written during the compilation.
 *   <li><b>logs</b> — everything this application logged, at TRACE, read through the chain-walking
 *       renderer so a value carried on a cause two levels down is found rather than missed.
 *   <li><b>canonical payload</b> — the exact bytes the digest is taken over.
 *   <li><b>digest</b> — see {@link #theDigestHasNoInputBesideThePayload} for why this one is a
 *       separate test and what it can honestly claim.
 * </ol>
 *
 * <h2>What stops eight zeroes from being free</h2>
 *
 * <p>Eight surfaces reporting "the secret is not here" is exactly what an empty pack, a failed
 * fixture or a route that 404s would also report. So every negative is paired: the same surface is
 * searched for {@code DATABASE_PASSWORD=[REDACTED]}, the marker that replaced the value, and has to
 * be positive. The source records are separately asserted to still hold the raw value, so the pack
 * was compiled from text that contained it rather than from records that never did.
 *
 * <p>Every value here is synthetic and high-entropy, and the {@code zqxw} run makes it
 * un-producible by a UUID at any length. Nothing is matched on a fragment.
 */
@AutoConfigureMockMvc
@ExtendWith(LoggerLevelIsolation.class)
class ContextShapelessSecretEightSurfaceTest extends ContextProbeFixture {

  /**
   * No shape any pattern recognises: not {@code sk-}, not {@code ghp_}, not a private key block,
   * not a database URL. This is the value the redactor used to miss entirely.
   */
  private static final String SHAPELESS = "S3cr3tP4ssw0rd_zqxw_881455";

  /** The same secret under a prefixed key — the spelling the {@code \b} anchor let through. */
  private static final String PREFIXED = "DATABASE_PASSWORD=" + SHAPELESS;

  /** What the redactor writes in its place, and therefore what every positive control looks for. */
  private static final String MARKER = "DATABASE_PASSWORD=[REDACTED]";

  /**
   * Tables holding records the user wrote themselves. The engine reads these; the guarantee is
   * about everything else. Kept in step with {@code ContextSchemaWideLeakTest}, which sweeps the
   * same schema for the bare-key probe.
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
  private UUID projectId;
  private Logger root;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void plantTheShapelessSecretInEverySource() {
    owner = identity.createAndAuthenticate("eight-surface-owner");
    Project project =
        projects.create(
            "Eight surface project configured with " + PREFIXED,
            "Records carrying " + PREFIXED + " because someone pasted it",
            "Guide a solo developer, deployed with " + PREFIXED);
    projectId = project.getId();

    RoadmapPhase phase =
        roadmaps.addPhase(projectId, 1, "Foundations", "Set up with " + PREFIXED);
    Task task =
        tasks.addTask(
            projectId,
            phase.getId(),
            1,
            "Wire the deployment",
            "Export " + PREFIXED + " before the run.",
            RiskLevel.LOW);
    tasks.addCriterion(projectId, task.getId(), "The pipeline connects using " + PREFIXED, true);
    evidence.record(
        projectId,
        task.getId(),
        EvidenceType.BUILD_RESULT,
        "BUILD FAILURE\nauth rejected for " + PREFIXED,
        "maven");

    // Title as well as body, in every entry type. The title becomes an item's label, which is a
    // column of its own: a redaction gap that covered content and missed labels would otherwise
    // leave every assertion here green with the label column full of the secret.
    for (BrainEntryType type : BrainEntryType.values()) {
      brain.add(
          projectId,
          type,
          "Entry of type " + type + " with " + PREFIXED,
          "Remembered as " + type + ": " + PREFIXED,
          "test");
    }

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

  /**
   * The control that makes the seven measurements below measurements of this system rather than of
   * a system that no longer exists.
   *
   * <p>While CTX-09B-1 was open the first assertion read {@code isEqualTo(PREFIXED)} — the input
   * returned untouched. If it ever reads that again, everything else in this class is describing a
   * redactor that has been reverted, and it should fail here first and say why.
   */
  @Test
  @DisplayName("The redactor recognises a prefixed key, which is the fix CTX-09B-1 required")
  void theRedactorRecognisesThePrefixedKey() {
    assertThat(SensitiveDataRedactor.redact(PREFIXED))
        .as("DATABASE_PASSWORD= is an assignment whether or not \\b sees a boundary before PASSWORD")
        .isEqualTo(MARKER)
        .doesNotContain(SHAPELESS);
    assertThat(SensitiveDataRedactor.redact("PASSWORD=" + SHAPELESS))
        .as("the same value under a bare key was always removed; the prefix no longer changes that")
        .isEqualTo("PASSWORD=[REDACTED]");
  }

  /**
   * The finding, re-run over the whole route: seven surfaces, measured separately, all zero.
   *
   * <p>The eighth — the digest — is a separate test because it is the one surface where "the secret
   * is not in it" is true for a reason that has nothing to do with redaction, and saying so in the
   * same map would let a decoration sit next to seven real measurements.
   */
  @Test
  @DisplayName("CTX-09B-1 closed: the shape-less secret reaches none of seven surfaces")
  void theShapelessSecretReachesNoneOfTheSevenSurfaces() throws Exception {
    Instant before = Instant.now().minusSeconds(1);

    String compileResponse =
        mvc.perform(
                post(url() + "/compile")
                    .with(TestIdentity.as(owner))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"taskReference\":\"CTX-09B-1 eight surface\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID packId = UUID.fromString(json.readTree(compileResponse).get("packId").asText());

    String singleRead =
        mvc.perform(get(url() + "/" + packId).with(TestIdentity.as(owner)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String listRead =
        mvc.perform(get(url()).with(TestIdentity.as(owner)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // The assembler is called for the canonical payload only: it is derived rather than stored, so
    // there is no column to read it out of. Same project, same records, same redaction path.
    // Re-authenticated first: MockMvc's request post-processor sets the security context for the
    // duration of the request and clears it afterwards, so a direct service call after an HTTP one
    // has no caller at all.
    identity.authenticateAs(owner);
    CompiledContextPack compiled =
        assembler.assemble(projectId, "CTX-09B-1 canonical", GENEROUS);
    assertThat(compiled.admittedItems())
        .as("a pack with no items would make every zero below free")
        .isNotEmpty();

    String auditRows = auditRowsSince(before);
    String logLines = String.join("\n", ourLogLines());

    Map<String, Boolean> surfaces = new LinkedHashMap<>();
    surfaces.put("database", !tablesHolding(SHAPELESS).isEmpty());
    surfaces.put("HTTP compile response", compileResponse.contains(SHAPELESS));
    surfaces.put("HTTP GET", singleRead.contains(SHAPELESS));
    surfaces.put("HTTP list", listRead.contains(SHAPELESS));
    surfaces.put("audit", auditRows.contains(SHAPELESS));
    surfaces.put("logs", logLines.contains(SHAPELESS));
    surfaces.put("canonical payload", compiled.canonicalPayload().value().contains(SHAPELESS));

    assertThat(surfaces)
        .as(
            "measured exposure of a shape-less secret under a prefixed key, surface by surface."
                + " Tables outside the user's own records still holding it: %s",
            tablesHolding(SHAPELESS))
        .containsExactly(
            Map.entry("database", false),
            Map.entry("HTTP compile response", false),
            Map.entry("HTTP GET", false),
            Map.entry("HTTP list", false),
            Map.entry("audit", false),
            Map.entry("logs", false),
            Map.entry("canonical payload", false));

    // ---- the other half: each of the same seven, positive on the marker or on the record.

    // The database, audit and logs are negative for three different reasons, and only the first is
    // redaction, so their controls are not the same control.
    assertThat(
            countIn(
                "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ? AND content LIKE ?",
                packId,
                "%" + MARKER + "%"))
        .as("the pack's rows carry the marker: redaction happened, the row was not simply absent")
        .isPositive();
    assertThat(
            countIn(
                "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ? AND label LIKE ?",
                packId,
                "%" + MARKER + "%"))
        .as("and so does the label column, which a content-only redaction would have left raw")
        .isPositive();
    assertThat(
            countIn(
                "SELECT COUNT(*) FROM brain_entries WHERE project_id = ? AND content LIKE ?",
                projectId,
                "%" + SHAPELESS + "%"))
        .as("the source records still hold the raw value: redaction happens on the way into a pack")
        .isPositive();

    assertThat(compileResponse)
        .as("the compile response is a real pack, carrying the marker where the value was")
        .contains(MARKER)
        .contains("\"packId\"");
    assertThat(singleRead).as("so is the single read").contains(MARKER);
    assertThat(listRead).as("and the list").contains(MARKER).contains(packId.toString());
    assertThat(compiled.canonicalPayload().value())
        .as("and the canonical payload the digest is taken over")
        .contains(MARKER);

    // Audit: the negative above is only worth something if audit events were written at all.
    assertThat(auditRows)
        .as("audit recorded that a pack was compiled; it is silent about what was in it")
        .isNotBlank();

    // Logs: the negative here is "the engine is silent", not "the engine logs carefully" — both
    // give the same guarantee today and only the second would survive someone adding a debug line,
    // so the distinction is stated rather than glossed. The control is that the appender received
    // anything at all; without it, a detached appender reports a clean log.
    assertThat(captured.list).as("the appender must have received events to search").isNotEmpty();
  }

  /**
   * The eighth surface, and the one where the honest claim is narrower than it looks.
   *
   * <p>A pack digest is sixty-four lowercase hexadecimal characters. It cannot contain
   * {@code S3cr3tP4ssw0rd_zqxw_881455} for the same reason it cannot contain a photograph, so
   * asserting {@code doesNotContain} on it would be decoration — it would pass over a digest taken
   * across a payload made entirely of the secret.
   *
   * <p>What can carry the secret is the payload the digest commits to, and that is measured in the
   * test above. What is left to assert here is the thing that would make that measurement stop
   * covering the digest: <b>that the payload is the digest's only input.</b> If a second input were
   * ever added beside it — the raw item text, the source record, anything not passed through the
   * redactor — the payload could stay clean while the digest stopped being a function of it, and
   * every quoted fingerprint would be a commitment to text nobody had checked.
   *
   * <p>So: the digest equals SHA-256 of the payload's own bytes, recomputed here rather than taken
   * from the pack, and it is hexadecimal. Both would fail the moment a second input appeared.
   */
  @Test
  @DisplayName("The digest has no input beside the canonical payload, which is what makes it safe")
  void theDigestHasNoInputBesideThePayload() {
    CompiledContextPack compiled = assembler.assemble(projectId, "CTX-09B-1 digest", GENEROUS);

    assertThat(compiled.packDigest())
        .as("the digest is a function of the payload and of nothing else")
        .isEqualTo(ContextDigest.sha256Hex(compiled.canonicalPayload().value()));
    assertThat(compiled.packDigest())
        .as("and it is hexadecimal, which is why it is the payload that has to be clean")
        .matches("[0-9a-f]{64}");
    assertThat(compiled.canonicalPayload().value())
        .as("the payload it commits to carries the marker, not the value")
        .contains(MARKER)
        .doesNotContain(SHAPELESS);
  }

  /**
   * Negative control. The same requests against a path that is not mapped answer 404 and compile
   * nothing, so a run in which the routes above silently stopped existing is distinguishable from a
   * run in which they answered cleanly.
   */
  @Test
  @DisplayName("Negative control: an unmapped sibling path serves none of these routes")
  void anUnmappedSiblingServesNothing() throws Exception {
    int before = countIn("SELECT COUNT(*) FROM context_packs");
    String dead = "/api/projects/" + projectId + "/context-eight-surface-not-a-route";

    mvc.perform(get(dead).with(TestIdentity.as(owner))).andExpect(status().isNotFound());
    mvc.perform(
            post(dead + "/compile")
                .with(TestIdentity.as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskReference\":\"DEAD\"}"))
        .andExpect(status().isNotFound());

    assertThat(countIn("SELECT COUNT(*) FROM context_packs"))
        .as("an unmapped path compiles nothing")
        .isEqualTo(before);
  }

  // ------------------------------------------------------------------ helpers

  private String url() {
    return "/api/projects/" + projectId + "/context";
  }

  /**
   * Every table in the schema holding the needle, minus the records the user wrote themselves.
   *
   * <p>Returned as a map rather than a boolean so that a failure names the table. Textual columns
   * read correctly, {@code TEXT}/CLOB included; a {@code byte[]} column would render as
   * {@code [B@...} and could never match, which is the same limitation
   * {@code ContextSchemaWideLeakTest} carries and states.
   */
  private Map<String, Integer> tablesHolding(String needle) {
    List<String> tables =
        jdbc.queryForList(
            "SELECT LOWER(table_name) FROM information_schema.tables"
                + " WHERE UPPER(table_schema) = 'PUBLIC' AND UPPER(table_type) = 'BASE TABLE'"
                + " ORDER BY 1",
            String.class);
    assertThat(tables)
        .as("the sweep must actually see the schema")
        .contains("context_packs", "context_pack_items", "audit_events", "brain_entries");

    Map<String, Integer> holding = new TreeMap<>();
    for (String table : tables) {
      if (USER_RECORD_TABLES.contains(table)) {
        continue;
      }
      int occurrences = 0;
      for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM \"" + table + "\"")) {
        for (Object value : row.values()) {
          if (value != null && String.valueOf(value).contains(needle)) {
            occurrences++;
          }
        }
      }
      if (occurrences > 0) {
        holding.put(table, occurrences);
      }
    }
    return holding;
  }

  /** Every audit event written since {@code since}, flattened to one searchable string. */
  private String auditRowsSince(Instant since) {
    StringBuilder rows = new StringBuilder();
    for (Map<String, Object> event :
        jdbc.queryForList("SELECT * FROM audit_events WHERE created_at >= ?", since)) {
      event.values().forEach(value -> rows.append(String.valueOf(value)).append(" | "));
      rows.append('\n');
    }
    return rows.toString();
  }

  /** Everything captured, rendered through the chain-walking renderer so causes are covered. */
  private List<String> ourLogLines() {
    List<String> lines = new ArrayList<>();
    for (ILoggingEvent event : List.copyOf(captured.list)) {
      lines.add(event.getLoggerName() + " @" + event.getLevel() + ": " + LogCapture.lineOf(event));
    }
    return lines;
  }

  private int countIn(String sql, Object... arguments) {
    Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
    return count == null ? 0 : count;
  }
}
