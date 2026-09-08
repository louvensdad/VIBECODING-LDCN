package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CurrentStateContextCollectorTest extends CollectorTestSupport {

  @Test
  @DisplayName("Computed state is collected, and dated at the records it was computed from")
  void computedStateIsCollected() {
    Fixture fixture = createFullProject("StateCollector");
    Instant beforeCollection = Instant.now();

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.CURRENT_STATE, fixture.projectId(), ContextReadWindow.DEFAULT);

    assertThat(items)
        .extracting(ContextItem::id)
        .containsExactlyInAnyOrder(
            "state:" + fixture.projectId() + ":progress",
            "state:" + fixture.projectId() + ":ready-tasks",
            "state:" + fixture.projectId() + ":phases-without-tasks");

    assertThat(items)
        .allSatisfy(
            item -> {
              assertThat(item.provenance().sourceType())
                  .isEqualTo(ContextSourceType.CURRENT_STATE);
              assertThat(item.provenance().sourceId()).isEqualTo(fixture.projectId().toString());
              // Dated at the newest contributing record, never at the moment of assembly.
              assertThat(item.provenance().recordedAt()).isBefore(beforeCollection);
            });

    ContextItem progress =
        items.stream().filter(item -> item.id().endsWith(":progress")).findFirst().orElseThrow();
    assertThat(progress.kind()).isEqualTo(ContextKind.CURRENT_STATE);
    assertThat(progress.content()).contains("0 of 2 tasks complete").contains("Blocked tasks: 1");

    ContextItem pending =
        items.stream()
            .filter(item -> item.id().endsWith(":phases-without-tasks"))
            .findFirst()
            .orElseThrow();
    assertThat(pending.kind()).isEqualTo(ContextKind.NEXT_STEP);
    assertThat(pending.content()).isEqualTo(fixture.phaseWithoutTasks().getTitle());
  }

  @Test
  @DisplayName("A phase nobody has broken down yet moves the state's observation instant")
  void stateIsDatedAtEveryRecordItIsComputedFrom() {
    Fixture fixture = createFullProject("StateStaleness");

    ContextItem before = pendingPhasesOf(fixture);
    roadmaps.addPhase(fixture.projectId(), 4, "Launch", "Planned, nobody has detailed it");
    ContextItem after = pendingPhasesOf(fixture);

    // phasesWithoutTasks is computed from every phase row, so a new empty phase changes it.
    assertThat(after.content()).isNotEqualTo(before.content());
    assertThat(after.content()).contains("Launch");

    // So the fold behind recordedAt has to see every phase, not only the current one. Folding just
    // the current phase leaves this item asserting an observation instant from before the record
    // it is built from existed. All three state items share one provenance, so getting this wrong
    // misdates the progress item too.
    assertThat(after.provenance().recordedAt()).isAfter(before.provenance().recordedAt());
  }

  private ContextItem pendingPhasesOf(Fixture fixture) {
    return candidates
        .collectFrom(
            ContextSourceType.CURRENT_STATE, fixture.projectId(), ContextReadWindow.DEFAULT)
        .stream()
        .filter(item -> item.id().endsWith(":phases-without-tasks"))
        .findFirst()
        .orElseThrow();
  }
}
