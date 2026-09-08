package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.roadmap.domain.RoadmapPhase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoadmapContextCollectorTest extends CollectorTestSupport {

  @Test
  @DisplayName("The outline and every phase are collected, finished phases included")
  void everyPhaseIsCollected() {
    Fixture fixture = createFullProject("RoadmapCollector");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.ROADMAP, fixture.projectId(), ContextReadWindow.DEFAULT);

    assertThat(items)
        .extracting(ContextItem::id)
        .contains(
            "roadmap-phase:" + fixture.firstPhase().getId(),
            "roadmap-phase:" + fixture.secondPhase().getId(),
            "roadmap-phase:" + fixture.phaseWithoutTasks().getId());

    ContextItem outline =
        items.stream().filter(item -> item.id().startsWith("roadmap:")).findFirst().orElseThrow();
    assertThat(outline.kind()).isEqualTo(ContextKind.OBJECTIVE);
    // The outline names every phase in plan order: nothing in the plan is invisible.
    assertThat(outline.content())
        .contains("1. " + fixture.firstPhase().getTitle())
        .contains("2. " + fixture.secondPhase().getTitle())
        .contains("3. " + fixture.phaseWithoutTasks().getTitle());

    ContextItem phase =
        items.stream()
            .filter(item -> item.id().equals("roadmap-phase:" + fixture.firstPhase().getId()))
            .findFirst()
            .orElseThrow();
    assertThat(phase.label()).isEqualTo(fixture.firstPhase().getTitle());
    assertThat(phase.content()).contains(fixture.firstPhase().getDescription());
    // The source type says "the plan"; the source id points at the exact phase row.
    assertThat(phase.provenance().sourceType()).isEqualTo(ContextSourceType.ROADMAP);
    assertThat(phase.provenance().sourceId())
        .isEqualTo(fixture.firstPhase().getId().toString());
    assertThat(phase.provenance().projectId()).isEqualTo(fixture.projectId());
  }

  @Test
  @DisplayName("The outline goes stale when the plan changes, and says so")
  void outlineIsDatedAtThePhasesItIsBuiltFrom() {
    Fixture fixture = createFullProject("RoadmapStaleness");

    ContextItem before = outlineOf(fixture);
    roadmaps.addPhase(fixture.projectId(), 4, "Launch", "Added after the first read");
    ContextItem after = outlineOf(fixture);

    // The outline's content is built entirely from the phase rows, so adding a phase changes it.
    assertThat(after.content()).isNotEqualTo(before.content());
    assertThat(after.content()).contains("4. Launch");

    // Therefore the instant it claims to have been observed at has to move too. Dating the outline
    // at Roadmap.updatedAt does not: that column is written once in the constructor and never
    // again, so the item would report the moment an empty roadmap row was inserted and go on
    // reporting it however much the plan changed underneath. An item that can never be seen as
    // stale is worse than one that is missing.
    assertThat(after.provenance().recordedAt()).isAfter(before.provenance().recordedAt());
  }

  @Test
  @DisplayName("The outline is observed at the newest record it was built from, not at T1")
  void outlineIsObservedAtTheLatestContributingRecord() {
    identity.createAndAuthenticate("RoadmapOutlineFreshness");
    var project = projects.create("Freshness", "", "An idea and a plan");
    roadmaps.createOrGet(project.getId());
    // T1: the roadmap exists and has nothing in it.
    Instant t1 = roadmaps.require(project.getId()).getUpdatedAt();

    RoadmapPhase phase = roadmaps.addPhase(project.getId(), 1, "Foundations", null);
    // T2: the newest of the records the outline is now built from.
    Instant phaseAt = roadmaps.requirePhase(project.getId(), phase.getId()).getUpdatedAt();
    Instant roadmapAt = roadmaps.require(project.getId()).getUpdatedAt();
    Instant t2 = phaseAt.isAfter(roadmapAt) ? phaseAt : roadmapAt;

    ContextItem outline = outlineOf(project.getId());

    assertThat(t2).as("the fixture is only meaningful if T2 really is later").isAfter(t1);
    // Observed at T2 and not at T1: the rule is the maximum over roadmap.updatedAt and every phase
    // that contributed a line, not the roadmap's own instant and not the newest phase alone.
    assertThat(outline.provenance().recordedAt()).isEqualTo(t2).isNotEqualTo(t1).isAfter(t1);
  }

  private ContextItem outlineOf(Fixture fixture) {
    return outlineOf(fixture.projectId());
  }

  private ContextItem outlineOf(UUID projectId) {
    return candidates
        .collectFrom(ContextSourceType.ROADMAP, projectId, ContextReadWindow.DEFAULT)
        .stream()
        .filter(item -> item.id().startsWith("roadmap:"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName("A project with no roadmap yields nothing, and does not fail")
  void noRoadmapYieldsNothing() {
    identity.createAndAuthenticate("RoadmapCollectorEmpty");
    var project = projects.create("Unplanned", "No roadmap yet", "An idea and nothing else");

    assertThat(
            candidates.collectFrom(
                ContextSourceType.ROADMAP, project.getId(), ContextReadWindow.DEFAULT))
        .isEmpty();
  }
}
