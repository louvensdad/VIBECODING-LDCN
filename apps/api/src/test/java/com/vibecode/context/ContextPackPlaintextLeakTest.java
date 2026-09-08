package com.vibecode.context;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vibecode.brain.application.BrainService;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.context.application.compiler.ContextPackAssembler;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextBudget;
import com.vibecode.project.application.ProjectService;
import com.vibecode.project.domain.Project;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.support.TestIdentity;
import com.vibecode.support.logging.LoggerLevelIsolation;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A credential written into a project's own records, and the two places it must not turn up.
 *
 * <p>The scenario is the realistic one. Nobody puts a secret into a context pack on purpose; a
 * developer pastes a working command into a decision entry, or into a task description, and months
 * later the context engine reads that record because it is exactly the kind of record it is
 * supposed to read. Whether the value reaches a prompt then depends entirely on whether redaction
 * ran before persistence rather than after it.
 *
 * <p>So this test asserts against the two surfaces that outlive the request: the {@code
 * context_packs} and {@code context_pack_items} rows, queried directly rather than through the
 * mapping that wrote them, and everything logged at DEBUG and above during the same run. A
 * credential in a response is caught in review; a credential in a log line is shipped to an
 * aggregator, indexed and retained.
 *
 * <p>The fixture is synthetic and is not a credential for anything. The source records themselves
 * keep the raw text - that is the user's data in the user's own tables, and this test makes no
 * claim about them. The claim is that it does not travel.
 *
 * <p>The root logger raised below belongs to the JVM, not to this class, so
 * {@link LoggerLevelIsolation} puts it back rather than an {@code @AfterEach} that only this file
 * would know about.
 */
@ExtendWith(LoggerLevelIsolation.class)
@SpringBootTest
class ContextPackPlaintextLeakTest {

  /** The agreed fixture. Synthetic; it opens nothing. */
  private static final String FIXTURE = "vc_context_secret_test_847293";

  private static final ContextBudget BUDGET = new ContextBudget(500, 1_000_000L, 2_000_000L);

  /**
   * The task reference, carrying the fixture on purpose.
   *
   * <p>This is not a contrived shape. A caller composes a reference from a task title the user
   * wrote - V9's own column comment anticipates {@code "TASK-42: " + a task title} - so whatever is
   * in that title arrives here. It lands in a column of its own, it is not an item, and it is not
   * measured, so nothing about the item pipeline touches it.
   *
   * <p>An earlier version of this test used the literal {@code "TASK-42"}, which carries nothing.
   * The column-by-column scan below was already looking at {@code task_reference} and would have
   * caught this on the first run; it passed only because the fixture never put anything there.
   */
  private static final String TASK_REFERENCE = "TASK-42: Deploy with TOKEN=" + FIXTURE;

  @Autowired ContextPackAssembler assembler;
  @Autowired TestIdentity identity;
  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TaskService tasks;
  @Autowired BrainService brain;
  @Autowired JdbcTemplate jdbc;

  private UUID projectId;
  private Logger root;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void createProjectAndStartCapturing() {
    identity.createAndAuthenticate("leak-owner");
    Project project =
        projects.create(
            "Leak fixture project",
            "A project whose records were written carelessly",
            "Guide a solo developer through building a small tool");
    projectId = project.getId();

    RoadmapPhase phase = roadmaps.addPhase(projectId, 1, "Foundations", "Set the shape");
    Task task =
        tasks.addTask(
            projectId,
            phase.getId(),
            1,
            "Wire the deployment",
            // Straight into a task description, which the policy admits in full.
            "Run the migration with TOKEN=" + FIXTURE + " exported first.",
            RiskLevel.LOW);
    tasks.addCriterion(
        projectId,
        task.getId(),
        // And into an acceptance criterion, which is admitted too.
        "The pipeline works with API_KEY=" + FIXTURE + " configured",
        true);

    // A decision entry, admitted by the standing-memory rule: title and body both carry it, because
    // a label leaks as effectively as a body and more quietly.
    brain.add(
        projectId,
        BrainEntryType.DECISION,
        "Deploy with TOKEN=" + FIXTURE,
        "We settled on deploying with TOKEN=" + FIXTURE + " until the vault work lands.",
        "test");

    // A note, which the policy refuses. Its content must not appear either - a refused item is
    // dropped, not stored somewhere for later display.
    brain.add(
        projectId,
        BrainEntryType.NOTE,
        "Scratch",
        "Left over: SECRET=" + FIXTURE,
        "test");

    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    // Deliberately noisy: a DEBUG line is still a line in a file somewhere. The level is not
    // remembered here; the extension on the class took a snapshot of every logger before this ran.
    root.setLevel(Level.DEBUG);
    captured = new ListAppender<>();
    captured.start();
    root.addAppender(captured);
  }

  @AfterEach
  void stopCapturing() {
    root.detachAppender(captured);
    captured.stop();
    identity.clear();
  }

  @Test
  @DisplayName("The raw fixture appears nowhere in the context tables, and the marker is there instead")
  void nothingRawReachesTheContextTables() {
    CompiledContextPack compiled = assembler.assemble(projectId, TASK_REFERENCE, BUDGET);

    // The pack really did pick up the records that carry the fixture, so the absences below are
    // about redaction rather than about the items never having been selected.
    assertThat(compiled.admittedItems()).isNotEmpty();
    List<AdmittedContextItem> markedItems =
        compiled.admittedItems().stream()
            .filter(admitted -> admitted.item().content().contains("[REDACTED]"))
            .toList();
    assertThat(markedItems)
        .as("at least one admitted item should have had a value removed from it")
        .isNotEmpty();

    // Every column of both tables, read back through plain SQL rather than through the mapping
    // that wrote them: an assertion made through the same code path that stored the row would be
    // testing the mapping against itself.
    assertThat(occurrencesIn("context_packs")).isZero();
    assertThat(occurrencesIn("context_pack_items")).isZero();

    // The pack's own task reference, specifically. It is checked by name as well as by the scan
    // above, because it is the one stored string that no item pipeline touches: it is redacted in
    // the compiler and nowhere else, and a regression there would look like a passing test the
    // moment somebody simplified the fixture back to a literal.
    String storedReference =
        jdbc.queryForObject(
            "SELECT task_reference FROM context_packs WHERE id = ?",
            String.class,
            compiled.packId());
    assertThat(storedReference).doesNotContain(FIXTURE);
    assertThat(storedReference).contains("[REDACTED]");
    assertThat(storedReference).startsWith("TASK-42: Deploy with TOKEN=");

    // And the digest was taken over the redacted reference, not the raw one, so the canonical
    // payload and the stored row are describing the same pack.
    assertThat(compiled.taskReference()).isEqualTo(storedReference);
    assertThat(compiled.canonicalPayload().value()).doesNotContain(FIXTURE);

    // And the redaction marker is present in the stored rows, so the value was replaced rather
    // than the whole item having been quietly dropped.
    Integer markedRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM context_pack_items i JOIN context_packs p ON p.id = i.pack_id "
                + "WHERE p.project_id = ? AND i.content LIKE '%[REDACTED]%'",
            Integer.class,
            projectId);
    assertThat(markedRows).isPositive();

    // The source record still holds the raw text. That is the user's own data in the user's own
    // table, and it is asserted here so the two zeros above cannot be explained by the fixture
    // never having existed.
    Integer rawInSource =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM brain_entries WHERE project_id = ? AND content LIKE ?",
            Integer.class,
            projectId,
            "%" + FIXTURE + "%");
    assertThat(rawInSource).isPositive();
  }

  @Test
  @DisplayName("Nothing this application logs during a compilation contains the fixture")
  void nothingRawReachesTheLogs() {
    assembler.assemble(projectId, TASK_REFERENCE, BUDGET);

    List<ILoggingEvent> events = List.copyOf(captured.list);
    assertThat(events).isNotEmpty();

    for (ILoggingEvent event : events) {
      if (!event.getLoggerName().startsWith("com.vibecode")) {
        continue;
      }
      String line = lineOf(event);
      assertThat(line).doesNotContain(FIXTURE);
      // Not a fragment of it either: a truncated log line is still a starting point.
      assertThat(line).doesNotContain(FIXTURE.substring(0, 20));
      assertThat(line).doesNotContain("847293");
    }
  }

  @Test
  @DisplayName("Nothing any logger says about a context pack contains the fixture")
  void nothingAboutThePackReachesTheLogsEither() {
    assembler.assemble(projectId, TASK_REFERENCE, BUDGET);

    List<ILoggingEvent> events = List.copyOf(captured.list);
    assertThat(events).isNotEmpty();

    // The previous test is scoped to this application's own loggers, and that scope is not a
    // convenience. At DEBUG, Hibernate dumps the field values of every managed entity it flushes,
    // so a task objective a developer wrote a credential into is printed by the ORM whether or not
    // a context pack is ever compiled. That is a real exposure and it is reported with this work,
    // but it belongs to logging configuration and to the task module, neither of which this change
    // may touch, and no redaction inside the context engine can reach it.
    //
    // What this test pins is the part that IS this engine's: whatever the ORM prints about a
    // context pack row is already redacted, because redaction ran before the row existed. So every
    // line mentioning the pack or its items is checked, at every logger, framework included.
    int packLines = 0;
    for (ILoggingEvent event : events) {
      String line = lineOf(event);
      if (line.contains("ContextPack") || line.contains("context_pack")) {
        packLines++;
        assertThat(line).doesNotContain(FIXTURE);
      }
    }
    assertThat(packLines)
        .as("the ORM should have said something about the pack it wrote")
        .isPositive();

    // And the exclusion above is pinned rather than assumed: any line that does carry the fixture
    // comes from outside this application and is about a source record, never about a pack. If
    // that ever stops being true, this fails and the scoping has to be revisited.
    for (ILoggingEvent event : events) {
      String line = lineOf(event);
      if (line.contains(FIXTURE)) {
        assertThat(event.getLoggerName()).doesNotStartWith("com.vibecode");
        assertThat(line).doesNotContain("ContextPack").doesNotContain("context_pack");
      }
    }
  }

  private static String lineOf(ILoggingEvent event) {
    return event.getFormattedMessage() + " " + String.valueOf(event.getThrowableProxy());
  }

  @Test
  @DisplayName("The content of a refused item is not stored so it can be shown later")
  void refusedContentIsNotKeptAnywhere() {
    assembler.assemble(projectId, TASK_REFERENCE, BUDGET);

    // The note was refused by name. Storing its text - even redacted, even in a side table - would
    // be storing what policy declined to show, which is the leak the refusal exists to prevent.
    Integer leftOver =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM context_pack_items i JOIN context_packs p ON p.id = i.pack_id "
                + "WHERE p.project_id = ? AND i.content LIKE '%Left over%'",
            Integer.class,
            projectId);
    assertThat(leftOver).isZero();
  }

  /** Every value of every column of one context table, as text, counted for the raw fixture. */
  private int occurrencesIn(String table) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT t.* FROM "
                + table
                + " t "
                + ("context_packs".equals(table)
                    ? "WHERE t.project_id = ?"
                    : "JOIN context_packs p ON p.id = t.pack_id WHERE p.project_id = ?"),
            projectId);
    assertThat(rows).as("the compiled pack should have been written").isNotEmpty();

    int occurrences = 0;
    for (Map<String, Object> row : rows) {
      for (Object value : row.values()) {
        if (value != null && String.valueOf(value).contains(FIXTURE)) {
          occurrences++;
        }
      }
    }
    return occurrences;
  }
}
