package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.output.domain.EvidenceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActiveErrorsContextCollectorTest extends CollectorTestSupport {

  @Test
  @DisplayName("A blocked task and a run needing correction are both active errors")
  void openProblemsAreCollected() {
    Fixture fixture = createFullProject("ActiveErrors");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.ACTIVE_ERRORS, fixture.projectId(), ContextReadWindow.DEFAULT);

    assertThat(items)
        .extracting(ContextItem::id)
        .containsExactlyInAnyOrder(
            "active-error:task:" + fixture.blockedTask().getId(),
            "active-error:analysis:" + fixture.failingAnalysisId());

    assertThat(items)
        .allSatisfy(
            item -> {
              assertThat(item.kind()).isEqualTo(ContextKind.ERROR);
              assertThat(item.provenance().sourceType())
                  .isEqualTo(ContextSourceType.ACTIVE_ERRORS);
              assertThat(item.provenance().projectId()).isEqualTo(fixture.projectId());
            });

    ContextItem blocked =
        items.stream()
            .filter(item -> item.id().startsWith("active-error:task:"))
            .findFirst()
            .orElseThrow();
    // The source id is the task row, so a reader can look the problem up and disagree with it.
    assertThat(blocked.provenance().sourceId())
        .isEqualTo(fixture.blockedTask().getId().toString());
    assertThat(blocked.content()).contains(fixture.blockedTask().getTitle());
  }

  @Test
  @DisplayName("An open correction outlives the read window, even as it falls out of LATEST_*")
  void openCorrectionsAreNotWindowed() {
    Fixture fixture = createFullProject("ActiveErrorsWindow");

    // Bury the failing run under newer evidence. A window of one is the same proof as fifty newer
    // rows against the default and costs a fraction of the time.
    for (int run = 0; run < 3; run++) {
      evidence.record(
          fixture.projectId(),
          fixture.blockedTask().getId(),
          EvidenceType.TEST_RESULT,
          "Tests run: 4, Failures: 0, Errors: 0",
          "maven");
    }
    ContextReadWindow narrow = new ContextReadWindow(1);

    // The windowed source has genuinely lost sight of it — that is the window doing its job.
    assertThat(
            candidates.collectFrom(
                ContextSourceType.LATEST_OUTPUT_ANALYSIS, fixture.projectId(), narrow))
        .extracting(ContextItem::id)
        .doesNotContain("analysis:" + fixture.failingAnalysisId());

    // So if ACTIVE_ERRORS read the same window, the failure would survive nowhere at all. An open
    // problem is bounded by the project, not by how fast evidence arrives, so it is not windowed.
    assertThat(candidates.collectFrom(ContextSourceType.ACTIVE_ERRORS, fixture.projectId(), narrow))
        .extracting(ContextItem::id)
        .contains("active-error:analysis:" + fixture.failingAnalysisId());
  }

  @Test
  @DisplayName("A remembered ERROR is not re-claimed as active, but is never lost either")
  void brainErrorsStaySomewhereEvenThoughTheyAreNotClaimedActive() {
    Fixture fixture = createFullProject("ActiveErrorsAndMemory");

    List<ContextItem> active =
        candidates.collectFrom(
            ContextSourceType.ACTIVE_ERRORS, fixture.projectId(), ContextReadWindow.DEFAULT);
    assertThat(active).extracting(ContextItem::id).noneMatch(id -> id.startsWith("brain:"));

    // The brain has no resolved marker, so the ACTIVE_ERRORS claim is withheld — the content is
    // not. It is collected in full under its own source, wearing the kind the mapping gave it.
    List<ContextItem> memory =
        candidates.collectFrom(
            ContextSourceType.BRAIN_ENTRY, fixture.projectId(), ContextReadWindow.DEFAULT);
    assertThat(memory)
        .filteredOn(item -> item.kind() == ContextKind.ERROR)
        .hasSize(1)
        .allSatisfy(
            item ->
                assertThat(item.label()).isEqualTo("Entry of type " + BrainEntryType.ERROR));
  }
}
