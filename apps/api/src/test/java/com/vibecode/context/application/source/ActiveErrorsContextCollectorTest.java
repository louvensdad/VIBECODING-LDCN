package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
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
