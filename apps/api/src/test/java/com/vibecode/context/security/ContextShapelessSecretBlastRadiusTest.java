package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.guardian.domain.SensitiveDataRedactor;
import com.vibecode.output.domain.EvidenceType;
import com.vibecode.project.domain.Project;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.shared.logging.LogCapture;
import com.vibecode.support.logging.LoggerLevelIsolation;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
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

/**
 * <b>This was a characterisation test. It is now a guarantee, over the same fixture and at the same
 * five measurement points.</b> It measures how far a secret with no recognisable shape, written
 * under a prefixed key, travels through the Context Engine. Every answer it recorded was
 * {@code true}; every answer it records now is {@code false}, and the change is in the redactor,
 * not in the measurement.
 *
 * <h2>The defect it measured, which was not a context defect</h2>
 *
 * <p>{@code SensitiveDataRedactor}'s key/value rule was anchored on {@code \b} before the key name,
 * and {@code _} is a word character, so there was no word boundary between a prefix and the key. A
 * bare {@code SECRET=} matched; {@code DATABASE_PASSWORD=} did not, and neither did {@code
 * OPENAI_API_KEY=}, {@code MY_SECRET=} or {@code GITHUB_TOKEN=}.
 *
 * <p>What rescued most real credentials is that the redactor has other patterns which match on the
 * <em>shape</em> of the value rather than on the key: an {@code sk-} key and a {@code ghp_} token
 * are removed whatever names them. So the exposure was narrower than "the redactor is broken" and
 * nastier than it sounded: <b>a credential with a recognisable shape was protected by its shape,
 * and a credential with no shape under a prefixed key was protected by nothing.</b> A password, a
 * base64 master key or an internal token is exactly that case.
 *
 * <p>SEC-RED-02 closed it in {@code guardian/**} by letting the key carry any prefix. The grammar
 * of what now counts as an assignment — and, as importantly, what does not, so that documentation
 * about passwords is not eaten — is written down in {@code SecretAssignmentGrammarTest}.
 *
 * <h2>What this class establishes</h2>
 *
 * <p>That the shape-less value reaches none of the surfaces it used to, measured rather than
 * reasoned, alongside a shaped value through the identical path. The negative results are held
 * honest by the contrast test below, which shows the same rows carrying redaction markers and the
 * source records still holding the raw values: the pack was built, it was built from records that
 * contain the secrets, and it contains neither. It also pins the two properties the Context Engine
 * owns and that held even while the redactor did not: the value reaches neither the audit trail nor
 * this application's own loggers.
 *
 * <p>Every other test in this package plants its probe behind a bare {@code TOKEN=}, which the
 * redactor does match. That is a deliberate choice and it is stated in {@link ContextProbeFixture}:
 * those tests are about the engine's redaction boundary, and giving them a value the redactor was
 * never going to catch would make them assert a Guardian defect instead. This class is where the
 * shape-less value under a prefixed key is planted on purpose — the spelling the redactor missed,
 * which is why it stays here now that it does not.
 */
@ExtendWith(LoggerLevelIsolation.class)
class ContextShapelessSecretBlastRadiusTest extends ContextProbeFixture {

  /**
   * No shape any pattern recognises: not {@code sk-}, not {@code ghp_}, not a private key block,
   * not a database URL. The {@code zqxw} run keeps it un-producible by a UUID.
   */
  private static final String SHAPELESS = "S3cr3tP4ssw0rd_zqxw_774301";

  /** The same secret under a prefixed key — the case the {@code \b} anchor used to let through. */
  private static final String PREFIXED_SHAPELESS = "DATABASE_PASSWORD=" + SHAPELESS;

  /** A value the redactor recognises by shape, whatever key names it. */
  private static final String SHAPED = "sk-proj-Ab3dEf6zqxw774302xyz";

  /** The shaped secret under an equally prefixed key, so only the shape differs. */
  private static final String PREFIXED_SHAPED = "OPENAI_API_KEY=" + SHAPED;

  private UUID projectId;
  private Logger root;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void plantBothSecretsInEverySource() {
    identity.createAndAuthenticate("blast-radius-owner");
    Project project =
        projects.create(
            "Blast radius project",
            "Records carrying " + PREFIXED_SHAPELESS + " and " + PREFIXED_SHAPED,
            "Guide a developer, configured with " + PREFIXED_SHAPELESS);
    projectId = project.getId();

    RoadmapPhase phase =
        roadmaps.addPhase(projectId, 1, "Foundations", "Set up with " + PREFIXED_SHAPELESS);
    Task task =
        tasks.addTask(
            projectId,
            phase.getId(),
            1,
            "Wire the deployment",
            "Export " + PREFIXED_SHAPELESS + " and " + PREFIXED_SHAPED + " before the run.",
            RiskLevel.LOW);
    tasks.addCriterion(
        projectId, task.getId(), "The pipeline connects using " + PREFIXED_SHAPELESS, true);

    evidence.record(
        projectId,
        task.getId(),
        EvidenceType.BUILD_RESULT,
        "BUILD FAILURE\nauth rejected for " + PREFIXED_SHAPELESS + " and " + PREFIXED_SHAPED,
        "maven");

    for (BrainEntryType type : BrainEntryType.values()) {
      // BOTH secrets go in the title as well as in the body. The shaped one is there for a reason
      // worth stating: the label assertion in theShapedSecretDivergesAtTheRedactor was vacuous
      // while only the body carried it -- no label in this fixture could contain SHAPED, so the
      // assertion held whatever the redactor did to labels, and a label-only redaction bypass left
      // it green with context_pack_items.label full of unredacted text. Now the label is a surface
      // the shaped secret genuinely reaches, and the assertion is about redaction again.
      brain.add(
          projectId,
          type,
          "Entry of type " + type + " with " + PREFIXED_SHAPELESS + " and " + PREFIXED_SHAPED,
          "Remembered as " + type + ": " + PREFIXED_SHAPELESS + " and " + PREFIXED_SHAPED,
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

  @Test
  @DisplayName("The redactor itself: a prefixed key is recognised, and so is a shape")
  void theRedactorBehavesAsMeasured() {
    // The control for everything below. If these three stop holding, the redactor has changed and
    // every measurement in this class is about a system that no longer exists. While the defect was
    // open the first of them read isEqualTo(PREFIXED_SHAPELESS) — the input returned untouched.
    assertThat(SensitiveDataRedactor.redact(PREFIXED_SHAPELESS))
        .as("DATABASE_PASSWORD= is an assignment whether or not \\b sees a boundary before PASSWORD")
        .isEqualTo("DATABASE_PASSWORD=[REDACTED]")
        .doesNotContain(SHAPELESS);

    assertThat(SensitiveDataRedactor.redact("PASSWORD=" + SHAPELESS))
        .as("the same value under a bare key was always removed; the prefix no longer changes that")
        .isEqualTo("PASSWORD=[REDACTED]")
        .doesNotContain(SHAPELESS);

    assertThat(SensitiveDataRedactor.redact(PREFIXED_SHAPED))
        .as("and the shape rule still names the kind of credential it removed")
        .doesNotContain(SHAPED)
        .contains("sk-****REDACTED****");
  }

  @Test
  @DisplayName("MEASURED: the shape-less secret reaches no content, label, payload or row")
  void theShapelessSecretReachesNoSurfaceAPackHas() {
    CompiledContextPack compiled = assembler.assemble(projectId, "CTX-09 blast", GENEROUS);
    assertThat(compiled.admittedItems()).isNotEmpty();

    Map<String, Boolean> surfaces = new LinkedHashMap<>();

    surfaces.put(
        "item content",
        compiled.admittedItems().stream()
            .anyMatch(admitted -> admitted.item().content().contains(SHAPELESS)));
    surfaces.put(
        "item label",
        compiled.admittedItems().stream()
            .anyMatch(admitted -> admitted.item().label().contains(SHAPELESS)));
    surfaces.put("canonical payload", compiled.canonicalPayload().value().contains(SHAPELESS));
    surfaces.put(
        "context_pack_items row",
        countIn("SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ? AND content LIKE ?",
                compiled.packId(), "%" + SHAPELESS + "%")
            > 0);
    surfaces.put(
        "context_pack_items label column",
        countIn("SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ? AND label LIKE ?",
                compiled.packId(), "%" + SHAPELESS + "%")
            > 0);

    // Every one of these was TRUE and every one of them is now FALSE. Asserted as the whole map so
    // a partial regression names the surface that came back rather than failing on the first.
    assertThat(surfaces)
        .as(
            "measured exposure of a shape-less secret under a prefixed key. Every entry read true"
                + " at 6d784fb; a true here is FINDING CTX-09B-1 reopening on that surface.")
        .containsExactly(
            java.util.Map.entry("item content", false),
            java.util.Map.entry("item label", false),
            java.util.Map.entry("canonical payload", false),
            java.util.Map.entry("context_pack_items row", false),
            java.util.Map.entry("context_pack_items label column", false));

    // Five falses are free if the pack is empty or the fixture never planted anything, which is how
    // this project has manufactured a green before. So: the same five surfaces, searched for the
    // marker that replaced the value. Each one has to be positive, from the same compiled pack.
    assertThat(compiled.admittedItems())
        .as("an item whose content carries the redacted assignment")
        .anySatisfy(
            admitted ->
                assertThat(admitted.item().content()).contains("DATABASE_PASSWORD=[REDACTED]"));
    assertThat(compiled.admittedItems())
        .as("and an item whose LABEL carries it, which is the column a label-only gap would fill")
        .anySatisfy(
            admitted ->
                assertThat(admitted.item().label()).contains("DATABASE_PASSWORD=[REDACTED]"));
    assertThat(compiled.canonicalPayload().value()).contains("DATABASE_PASSWORD=[REDACTED]");
    assertThat(
            countIn(
                "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ? AND content LIKE ?",
                compiled.packId(),
                "%DATABASE_PASSWORD=[REDACTED]%"))
        .isPositive();
    assertThat(
            countIn(
                "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ? AND label LIKE ?",
                compiled.packId(),
                "%DATABASE_PASSWORD=[REDACTED]%"))
        .isPositive();
    // And the source records still hold the raw value, so the pack was compiled from text that
    // contained it rather than from records that never did.
    assertThat(
            countIn(
                "SELECT COUNT(*) FROM brain_entries WHERE project_id = ? AND content LIKE ?",
                projectId,
                "%" + SHAPELESS + "%"))
        .as("the fixture's own records are untouched: redaction happens on the way into a pack")
        .isPositive();

    // The digest is now a digest over a payload that does not contain the secret, which is what
    // makes a pack's fingerprint safe to quote. It was not before.
    assertThat(compiled.packDigest()).hasSize(64);
  }

  @Test
  @DisplayName("CONTRAST: the shaped secret, planted identically, reaches none of them")
  void theShapedSecretDivergesAtTheRedactor() {
    CompiledContextPack compiled = assembler.assemble(projectId, "CTX-09 contrast", GENEROUS);

    for (AdmittedContextItem admitted : compiled.admittedItems()) {
      assertThat(admitted.item().content()).as("content of %s", admitted.id()).doesNotContain(SHAPED);
      assertThat(admitted.item().label()).as("label of %s", admitted.id()).doesNotContain(SHAPED);
    }
    assertThat(compiled.canonicalPayload().value()).doesNotContain(SHAPED);
    assertThat(
            countIn(
                "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ? AND content LIKE ?",
                compiled.packId(),
                "%" + SHAPED + "%"))
        .isZero();

    assertThat(
            countIn(
                "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ? AND label LIKE ?",
                compiled.packId(),
                "%" + SHAPED + "%"))
        .as("nor the label column, which is the surface a label-only redaction gap would fill")
        .isZero();

    // Non-vacuous, and in both columns. The shaped secret was planted in the same rows as the
    // shape-less one -- in the body AND in the title -- and it is still sitting in both. Its
    // absence above is redaction, not a fixture that forgot to write it. The title half of this is
    // what stops the label assertions from being satisfied by a fixture that never had a shaped
    // secret in a label to begin with.
    assertThat(
            countIn(
                "SELECT COUNT(*) FROM brain_entries WHERE project_id = ? AND content LIKE ?",
                projectId,
                "%" + SHAPED + "%"))
        .as("the shaped secret is in the source records it was written to")
        .isPositive();
    assertThat(
            countIn(
                "SELECT COUNT(*) FROM brain_entries WHERE project_id = ? AND title LIKE ?",
                projectId,
                "%" + SHAPED + "%"))
        .as("and in their titles, which is where the labels above were read from")
        .isPositive();
    assertThat(
            countIn(
                "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ? AND label LIKE ?",
                compiled.packId(),
                "%sk-****REDACTED****%"))
        .as("so a label really did pass through the redactor and come out carrying the marker")
        .isPositive();
    assertThat(
            countIn(
                "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ? AND content LIKE ?",
                compiled.packId(),
                "%sk-****REDACTED****%"))
        .as("and the marker that replaced it is in the pack")
        .isPositive();
  }

  @Test
  @DisplayName("Either way, the value reaches neither the audit trail nor this application's logs")
  void thePropertiesTheContextEngineOwnsHoldRegardless() {
    java.time.Instant before = java.time.Instant.now().minusSeconds(1);
    CompiledContextPack compiled = assembler.assemble(projectId, "CTX-09 boundaries", GENEROUS);
    assertThat(compiled.admittedItems()).isNotEmpty();

    List<Map<String, Object>> events =
        jdbc.queryForList("SELECT * FROM audit_events WHERE created_at >= ?", before);
    for (Map<String, Object> event : events) {
      StringBuilder row = new StringBuilder();
      event.values().forEach(value -> row.append(String.valueOf(value)).append(" | "));
      assertThat(row.toString())
          .as("audit records that a pack was compiled, never what was in it")
          .doesNotContain(SHAPELESS)
          .doesNotContain(SHAPED);
    }

    // The capture is live over the whole run — that is the control. Note what it shows about the
    // module: not one com.vibecode logger says anything during a successful compilation, so the
    // clean result below is "the engine is silent", not "the engine logs carefully". Both give the
    // same guarantee today; only the second would survive someone adding a debug line.
    List<ILoggingEvent> all = List.copyOf(captured.list);
    assertThat(all).as("the appender must have received events to search").isNotEmpty();

    List<String> mentions = new ArrayList<>();
    for (ILoggingEvent event : all) {
      String line = LogCapture.lineOf(event);
      if (line.contains(SHAPELESS) || line.contains(SHAPED)) {
        mentions.add(event.getLoggerName() + " @" + event.getLevel() + ": " + line);
      }
    }
    assertThat(mentions)
        .as("no logger of ours emits the value, redacted or not")
        .allSatisfy(line -> assertThat(line).doesNotStartWith("com.vibecode"));
  }

  private int countIn(String sql, Object... arguments) {
    Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
    return count == null ? 0 : count;
  }
}
