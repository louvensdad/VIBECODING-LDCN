package com.vibecode.context.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.context.application.redaction.ContextRedaction;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextPack;
import com.vibecode.context.domain.ContextPolicyVersion;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.project.application.ProjectService;
import com.vibecode.support.TestIdentity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * That the caps the domain enforces are the widths the database actually has.
 *
 * <p>{@code ContextItemLabelLengthTest} and {@code ContextPackTaskReferenceLengthTest} pin where an
 * over-long value is refused. This one pins the other half: a value at exactly the cap must survive
 * a real write and a real read, and the cap must be the column's real width. Without the second
 * half a cap could be any number at all and every domain test would still pass while the column
 * quietly rejected the top of the accepted range.
 *
 * <p>There is no 501 case reaching a write here, and that is the result rather than a gap in the
 * coverage: 501 can no longer be constructed. Before the caps it reached one and failed with
 * {@code Value too long for column "label CHARACTER VARYING(500)"} and, for the pack's own field,
 * the same message naming {@code task_reference}.
 *
 * <p>All fixtures are synthetic.
 */
@SpringBootTest
class ContextPackTextWidthBoundaryTest {

  private static final Instant OBSERVED_AT = Instant.parse("2026-03-01T10:15:30Z");
  private static final Instant ASSEMBLED_AT = Instant.parse("2026-03-01T10:16:00Z");

  /** Generous: these tests are about column widths, not about where a budget binds. */
  private static final ContextBudget BUDGET = new ContextBudget(50, 100_000L, 200_000L);

  private static final ContextAdmission FIXTURE_ADMISSION =
      ContextAdmission.allow(
          "test.fixture.text-width",
          "A synthetic admission used by the text width boundary fixtures.");

  @Autowired ContextPackRepository packs;
  @Autowired ProjectService projects;
  @Autowired TestIdentity identity;
  @Autowired JdbcTemplate jdbc;

  private UUID projectId;

  @BeforeEach
  void createProject() {
    identity.createAndAuthenticate("text-width-owner");
    projectId = projects.create("Text width project", "", "Uma ideia sintetica").getId();
  }

  @AfterEach
  void signOut() {
    identity.clear();
  }

  /** Writes a one-item pack and hands back what the database returned. */
  private ContextPack storedPack(String label, String taskReference) {
    ContextItem item =
        new ContextItem(
            "i-width-boundary",
            ContextKind.DECISION,
            label,
            "synthetic content",
            new ContextProvenance(
                ContextSource.of(ContextSourceType.BRAIN_ENTRY, "dec-1"), projectId, OBSERVED_AT));
    UUID packId = UUID.randomUUID();
    packs.saveAndFlush(
        ContextPackEntity.from(
            new CompiledContextPack(
                packId,
                projectId,
                taskReference,
                ASSEMBLED_AT,
                BUDGET,
                ContextPolicyVersion.CURRENT,
                List.of(
                    new AdmittedContextItem(ContextRedaction.redact(item), FIXTURE_ADMISSION)))));
    return packs.findById(packId).orElseThrow().toDomain();
  }

  /** The declared width of one column in the schema Flyway actually applied. */
  private int declaredWidthOf(String table, String column) {
    return jdbc.queryForObject(
        "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS"
            + " WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
        Integer.class,
        table,
        column);
  }

  @Test
  @DisplayName("A label and a task reference at the cap are written and read back whole")
  void valuesUpToTheCapSurviveTheRoundTrip() {
    ContextPack justUnder = storedPack("L".repeat(499), "T".repeat(499));
    assertThat(justUnder.items().get(0).label()).hasSize(499).isEqualTo("L".repeat(499));
    assertThat(justUnder.taskReference()).hasSize(499).isEqualTo("T".repeat(499));

    // The boundary itself, through real INSERTs. Truncation would be the quieter failure, so the
    // values are compared and not just their lengths.
    String labelAtCap = "L".repeat(ContextItem.MAX_LABEL_LENGTH);
    String referenceAtCap = "T".repeat(ContextPack.MAX_TASK_REFERENCE_LENGTH);
    ContextPack atCap = storedPack(labelAtCap, referenceAtCap);
    assertThat(atCap.items().get(0).label()).hasSize(500).isEqualTo(labelAtCap);
    assertThat(atCap.taskReference()).hasSize(500).isEqualTo(referenceAtCap);
  }

  @Test
  @DisplayName("An over-long value never reaches a write, label and task reference alike")
  void overLongValuesDoNotReachTheDatabase() {
    assertThatThrownBy(() -> storedPack("L".repeat(501), "TASK-42"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("may not exceed 500");

    assertThatThrownBy(() -> storedPack("A label", "T".repeat(501)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("may not exceed 500");

    // The redaction cases, which are the ones that used to get all the way to an INSERT: 498
    // characters in, 507 out, because the marker is longer than the value it replaced.
    assertThatThrownBy(() -> storedPack("L".repeat(490) + " TOKEN=x", "TASK-42"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Redaction lengthened item i-width-boundary")
        .hasMessageContaining("label 498 -> 507");

    String lengthenedReference = ContextRedaction.redactTaskReference("T".repeat(490) + " TOKEN=x");
    assertThat(lengthenedReference).hasSize(507);
    assertThatThrownBy(() -> storedPack("A label", lengthenedReference))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("has 507");
  }

  /**
   * The tripwire, and an honest account of what it catches.
   *
   * <p>It reads the width from {@code INFORMATION_SCHEMA} of the schema Flyway just applied, so it
   * is a comparison against V9 as executed rather than against a number retyped into a test. The
   * case it catches is the realistic one: a later migration widens or narrows a column and the
   * domain constant is left behind. That fails here, with both numbers named.
   *
   * <p><b>What it does not catch, stated plainly: a coordinated edit.</b> Someone who writes a
   * migration and changes the constant in the same commit passes this test — correctly, because at
   * that point the two agree. This is a consistency check, not a proof that either number is right,
   * and it cannot tell an intended widening from an accidental one. It also only ever sees the test
   * database: H2 in PostgreSQL mode applies the same V9, so the widths are V9's, but anything the
   * real PostgreSQL does differently with those declarations is outside what this can observe.
   *
   * <p>{@code context_pack_items.explanation} is deliberately absent. It is 500 wide like the other
   * two, but nothing in the domain caps it and nothing should: it is fixed prose from {@code
   * DefaultContextPolicyRules}, it is not user text, and redaction never touches it, so the growth
   * mechanism these caps exist for cannot reach it. Asserting a domain constant against its width
   * would advertise a guard that is not there.
   */
  @Test
  @DisplayName("The domain caps equal the column widths in the schema Flyway applied")
  void theCapsMatchTheAppliedSchema() {
    assertThat(declaredWidthOf("CONTEXT_PACK_ITEMS", "LABEL"))
        .as("V9 context_pack_items.label vs ContextItem.MAX_LABEL_LENGTH")
        .isEqualTo(ContextItem.MAX_LABEL_LENGTH);

    assertThat(declaredWidthOf("CONTEXT_PACKS", "TASK_REFERENCE"))
        .as("V9 context_packs.task_reference vs ContextPack.MAX_TASK_REFERENCE_LENGTH")
        .isEqualTo(ContextPack.MAX_TASK_REFERENCE_LENGTH);
  }
}
