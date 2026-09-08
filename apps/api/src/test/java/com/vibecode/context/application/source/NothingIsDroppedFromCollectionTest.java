package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The governing rule, as an assertion: collection is not inclusion.
 *
 * <p>A project is built holding a record of every source there is, and every one of those records
 * has to come back as a candidate. Nothing is dropped for being finished, satisfied, superseded,
 * noisy, or unlikely to be chosen. Whether any of it reaches a reader is the selection step's
 * decision, made later and deny-by-default — and it can only be made about items that still exist.
 */
class NothingIsDroppedFromCollectionTest extends CollectorTestSupport {

  @Test
  @DisplayName("Every source that has a record reports it, and every record is traceable")
  void everySourceIsRepresented() {
    Fixture fixture = createFullProject("NothingDropped");

    List<ContextItem> items = candidates.collect(fixture.projectId());
    Set<ContextSourceType> represented =
        items.stream().map(item -> item.provenance().sourceType()).collect(Collectors.toSet());

    // The fixture writes something for every source, so every source must answer.
    assertThat(represented).containsExactlyInAnyOrder(ContextSourceType.values());

    Set<String> ids = items.stream().map(ContextItem::id).collect(Collectors.toSet());
    assertThat(ids)
        .contains(
            "project:" + fixture.projectId() + ":idea",
            "state:" + fixture.projectId() + ":progress",
            "roadmap-phase:" + fixture.firstPhase().getId(),
            "roadmap-phase:" + fixture.phaseWithoutTasks().getId(),
            "current-phase:" + fixture.firstPhase().getId(),
            "current-task:" + fixture.blockedTask().getId() + ":objective",
            "criterion:" + fixture.requiredCriterion().getId(),
            "criterion:" + fixture.optionalCriterion().getId(),
            "evidence:" + fixture.failingEvidenceId(),
            "analysis:" + fixture.failingAnalysisId(),
            "active-error:task:" + fixture.blockedTask().getId(),
            "active-error:analysis:" + fixture.failingAnalysisId(),
            "security-summary:" + fixture.projectId());

    // Every brain entry the project holds, with no exception for the quiet ones.
    for (BrainEntry entry : brain.list(fixture.projectId())) {
      assertThat(ids).as("brain entry of type %s", entry.getType())
          .contains("brain:" + entry.getId());
    }

    // Ids are unique, which is what makes the canonical order total and what lets a pack refuse
    // duplicates without silently discarding one of two different items.
    assertThat(ids).hasSize(items.size());

    // And every single item can name the record behind it.
    assertThat(items)
        .allSatisfy(
            item -> {
              assertThat(item.provenance().sourceId()).isNotBlank();
              assertThat(item.provenance().projectId()).isEqualTo(fixture.projectId());
              assertThat(item.provenance().recordedAt()).isNotNull();
            });
  }

  @Test
  @DisplayName("Candidates come back in the canonical order, so a pack can be rebuilt from them")
  void candidatesAreCanonicallyOrdered() {
    Fixture fixture = createFullProject("NothingDroppedOrder");

    List<ContextItem> items = candidates.collect(fixture.projectId());

    assertThat(items).isSortedAccordingTo(ContextItem.CANONICAL_ORDER);
  }
}
