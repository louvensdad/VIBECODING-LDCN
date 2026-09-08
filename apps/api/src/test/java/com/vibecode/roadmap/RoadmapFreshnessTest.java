package com.vibecode.roadmap;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.project.application.ProjectService;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.support.TestIdentity;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.RiskLevel;
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

    // Roadmap stamps updatedAt from Instant.now(), and the column keeps microseconds. Creating the
    // roadmap and adding a phase can land inside the same microsecond, and then the new stamp is
    // equal to the old one and the assertion below fails - not because nothing was recorded, but
    // because the clock had not moved far enough to record it. That was a real intermittent
    // failure, not a theoretical one.
    //
    // So the clock is given a chance to advance before the structural change, not after. The
    // assertion is untouched and stays strictly-after: relaxing it to isAfterOrEqualTo would make
    // the test pass against a Roadmap that never stamped anything, which is the whole property it
    // exists to prove.
    awaitClockStrictlyPast(beforeAdd);

    roadmaps.addPhase(projectId, 1, "Foundations", null);

    assertThat(roadmaps.require(projectId).getUpdatedAt()).isAfter(beforeAdd);
  }

  /**
   * Blocks until {@link Instant#now()} is at least a microsecond past {@code recorded}.
   *
   * <p>A microsecond and not a nanosecond, because {@code recorded} came back from a column that
   * stores microseconds: a nanosecond-later stamp would be written and then rounded back onto the
   * same microsecond, and the wait would have bought nothing. Rounding is monotonic, so a stamp
   * taken at least a microsecond later cannot round back to or below {@code recorded}.
   *
   * <p>A spin rather than a sleep: the wait is sub-microsecond in practice, and a sleep would cost
   * a millisecond every run to solve a problem that has already gone away by the time it returns.
   */
  private static void awaitClockStrictlyPast(Instant recorded) {
    Instant target = recorded.plusNanos(1_000);
    while (Instant.now().isBefore(target)) {
      Thread.onSpinWait();
    }
  }

  @Test
  @DisplayName("reordering phases is a structural change and moves the roadmap's own instant")
  void reorderingMovesTheRoadmapsInstant() {
    UUID projectId = newPlannedProject("Reordem");
    roadmaps.addPhase(projectId, 1, "Foundations", null);
    RoadmapPhase second = roadmaps.addPhase(projectId, 2, "Delivery", null);
    Instant beforeMove = roadmaps.require(projectId).getUpdatedAt();

    // Same microsecond race as in the test above: the stamp taken when the second phase was added
    // and the one taken when the reorder completes can land on the same stored microsecond, and
    // then a roadmap that recorded the reorder perfectly still fails the assertion. There is more
    // work between the two stamps here — two renumbering passes and their flushes — so it loses the
    // race less often, not never.
    awaitClockStrictlyPast(beforeMove);

    roadmaps.movePhase(projectId, second.getId(), 1);

    assertThat(roadmaps.require(projectId).getUpdatedAt()).isAfter(beforeMove);
  }

  @Test
  @DisplayName("moving a phase to the position it already holds changes nothing, and says nothing")
  void aNoOpMoveMovesNeitherInstant() {
    UUID projectId = newPlannedProject("Sem movimento");
    RoadmapPhase first = roadmaps.addPhase(projectId, 1, "Foundations", null);
    roadmaps.addPhase(projectId, 2, "Delivery", null);
    Instant roadmapBefore = roadmaps.require(projectId).getUpdatedAt();
    Instant phaseBefore = roadmaps.requirePhase(projectId, first.getId()).getUpdatedAt();

    // A caller asking for the position a phase already holds is not making a mistake, so this
    // succeeds. It just does not do anything, and nothing that did not happen may be recorded:
    // an instant that moves for a no-op is exactly the false freshness ContextProvenance exists to
    // prevent, and it would make the roadmap outline read as newly observed in every later pack.
    List<RoadmapPhase> after = roadmaps.movePhase(projectId, first.getId(), 1);

    assertThat(after).extracting(RoadmapPhase::getTitle).containsExactly("Foundations", "Delivery");
    assertThat(roadmaps.require(projectId).getUpdatedAt()).isEqualTo(roadmapBefore);
    // The phase too: RoadmapPhase.moveTo stamps unconditionally, so the guard has to sit before
    // any mutation or the false freshness merely leaks through the phase row instead.
    assertThat(roadmaps.requirePhase(projectId, first.getId()).getUpdatedAt())
        .isEqualTo(phaseBefore);
  }

  @Test
  @DisplayName("a phase's own change is the phase's business and leaves the roadmap's instant alone")
  void aPhasesOwnChangeDoesNotMoveTheRoadmapsInstant() {
    UUID projectId = newPlannedProject("Interno");
    RoadmapPhase phase = roadmaps.addPhase(projectId, 1, "Foundations", null);
    Instant roadmapBefore = roadmaps.require(projectId).getUpdatedAt();
    Instant phaseBefore = roadmaps.requirePhase(projectId, phase.getId()).getUpdatedAt();

    // Only the phase's side of this test races: it asserts strictly-after against a stamp taken
    // when the phase was constructed, and the status recomputation below lands on that same stored
    // microsecond often enough to have been caught doing it — rarely, because adding a task is a
    // lot of work between the two stamps, but rarely is not never. The roadmap's side asserts
    // equality and is immune, since nothing writes that column at all, so the wait is against the
    // phase's stamp and not the roadmap's.
    awaitClockStrictlyPast(phaseBefore);

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
