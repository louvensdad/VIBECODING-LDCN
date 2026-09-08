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

/**
 * That the label cap the domain enforces is the width the database actually has.
 *
 * <p>{@code ContextItemLabelLengthTest} pins where an over-long label is refused. This one pins the
 * other half: a label at exactly the cap must survive a real write and a real read. Without it the
 * cap could be set to any number at all and every domain test would still pass, while the column
 * quietly rejected the top of the accepted range.
 *
 * <p>There is no 501 case here, and that is the point of the change rather than a gap in it: 501
 * can no longer be constructed, so it can no longer reach a write. Before the cap it reached one
 * and failed there with {@code Value too long for column "label CHARACTER VARYING(500)"}.
 *
 * <p>All fixtures are synthetic.
 */
@SpringBootTest
class ContextPackLabelBoundaryTest {

  private static final Instant OBSERVED_AT = Instant.parse("2026-03-01T10:15:30Z");
  private static final Instant ASSEMBLED_AT = Instant.parse("2026-03-01T10:16:00Z");

  /** Generous: this test is about a column width, not about where a budget binds. */
  private static final ContextBudget BUDGET = new ContextBudget(50, 100_000L, 200_000L);

  private static final ContextAdmission FIXTURE_ADMISSION =
      ContextAdmission.allow(
          "test.fixture.label-boundary",
          "A synthetic admission used by the label boundary fixtures.");

  @Autowired ContextPackRepository packs;
  @Autowired ProjectService projects;
  @Autowired TestIdentity identity;

  private UUID projectId;

  @BeforeEach
  void createProject() {
    identity.createAndAuthenticate("label-boundary-owner");
    projectId = projects.create("Label boundary project", "", "Uma ideia sintetica").getId();
  }

  @AfterEach
  void signOut() {
    identity.clear();
  }

  /** Writes a one-item pack and returns the label as the database gave it back. */
  private String storedLabelOf(String label) {
    ContextItem item =
        new ContextItem(
            "i-label-boundary",
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
                "TASK-CTX-LABEL",
                ASSEMBLED_AT,
                BUDGET,
                ContextPolicyVersion.CURRENT,
                List.of(
                    new AdmittedContextItem(ContextRedaction.redact(item), FIXTURE_ADMISSION)))));
    return packs.findById(packId).orElseThrow().toDomain().items().get(0).label();
  }

  @Test
  @DisplayName("A label at the cap is written and read back whole, and so is one below it")
  void labelsUpToTheCapSurviveTheRoundTrip() {
    assertThat(storedLabelOf("L".repeat(499))).hasSize(499).isEqualTo("L".repeat(499));

    // The boundary itself, through a real INSERT: 500 is inside the column, not one past it.
    // Truncation would be the quieter failure here, so the value is compared and not just its
    // length.
    String atTheCap = "L".repeat(ContextItem.MAX_LABEL_LENGTH);
    assertThat(storedLabelOf(atTheCap)).hasSize(500).isEqualTo(atTheCap);
  }

  @Test
  @DisplayName("An over-long label never reaches a write: it is refused before a pack is built")
  void overLongLabelsDoNotReachTheDatabase() {
    assertThatThrownBy(() -> storedLabelOf("L".repeat(501)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("may not exceed 500");

    // And the redaction case, which is the one that used to get all the way to the INSERT: 498
    // characters in, 507 out. It fails in ContextRedaction now — before the admission is attached,
    // before the budget measures anything and before a row is composed.
    assertThatThrownBy(() -> storedLabelOf("L".repeat(490) + " TOKEN=x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("has 507");
  }
}
