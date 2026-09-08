package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
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
