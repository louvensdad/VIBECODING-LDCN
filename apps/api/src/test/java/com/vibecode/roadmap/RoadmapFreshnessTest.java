package com.vibecode.roadmap;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.project.application.ProjectService;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.support.TestIdentity;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.RiskLevel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pins what {@code Roadmap.updatedAt} means, in both directions.
 *
 * <p>A single test that the instant moves would pass against a column touched by every descendant
 * change, and a single test that it holds still would pass against the column as it used to be —
 * written once in the constructor and never again. Only the pair says "structural changes, and
 * nothing else".
 */
@SpringBootTest
class RoadmapFreshnessTest {

  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TaskService tasks;
  @Autowired TestIdentity identity;

  @BeforeEach
  void authenticate() {
    identity.createAndAuthenticate("RoadmapFreshnessOwner");
  }

  @AfterEach
  void signOut() {
    identity.clear();
  }

  private UUID newPlannedProject(String name) {
    UUID projectId = projects.create(name, "", "An idea for " + name).getId();
    roadmaps.createOrGet(projectId);
    return projectId;
  }

  @Test
  @DisplayName("adding a phase is a structural change and moves the roadmap's own instant")
  void addingAPhaseMovesTheRoadmapsInstant() {
    UUID projectId = newPlannedProject("Estrutura");
    Instant beforeAdd = roadmaps.require(projectId).getUpdatedAt();

    roadmaps.addPhase(projectId, 1, "Foundations", null);

    assertThat(roadmaps.require(projectId).getUpdatedAt()).isAfter(beforeAdd);
  }

  @Test
  @DisplayName("reordering phases is a structural change and moves the roadmap's own instant")
  void reorderingMovesTheRoadmapsInstant() {
    UUID projectId = newPlannedProject("Reordem");
    roadmaps.addPhase(projectId, 1, "Foundations", null);
    RoadmapPhase second = roadmaps.addPhase(projectId, 2, "Delivery", null);
    Instant beforeMove = roadmaps.require(projectId).getUpdatedAt();

    roadmaps.movePhase(projectId, second.getId(), 1);

    assertThat(roadmaps.require(projectId).getUpdatedAt()).isAfter(beforeMove);
  }

  @Test
  @DisplayName("a phase's own change is the phase's business and leaves the roadmap's instant alone")
  void aPhasesOwnChangeDoesNotMoveTheRoadmapsInstant() {
    UUID projectId = newPlannedProject("Interno");
    RoadmapPhase phase = roadmaps.addPhase(projectId, 1, "Foundations", null);
    Instant roadmapBefore = roadmaps.require(projectId).getUpdatedAt();
    Instant phaseBefore = roadmaps.requirePhase(projectId, phase.getId()).getUpdatedAt();

    // Adding a task makes the status calculator recompute the phase's status, which is exactly the
    // kind of descendant change that must not be reported as a change to the plan's shape.
    tasks.addTask(
        projectId, phase.getId(), 1, "Do the work", "Something inside the phase", RiskLevel.LOW);

    assertThat(roadmaps.requirePhase(projectId, phase.getId()).getUpdatedAt())
        .as("the phase itself did change, so this test is not passing by accident")
        .isAfter(phaseBefore);
    assertThat(roadmaps.require(projectId).getUpdatedAt())
        .as("the shape of the plan did not change, so the roadmap's instant must not move")
        .isEqualTo(roadmapBefore);
  }
}
