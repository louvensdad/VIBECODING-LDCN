package com.vibecode.roadmap.application;

import com.vibecode.project.application.ProjectService;
import com.vibecode.roadmap.domain.Roadmap;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.roadmap.infrastructure.RoadmapPhaseRepository;
import com.vibecode.roadmap.infrastructure.RoadmapRepository;
import com.vibecode.shared.domain.DomainRuleException;
import com.vibecode.shared.domain.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the structure of the plan: the roadmap and its ordered phases. */
@Service
@Transactional
public class RoadmapService {

  private final ProjectService projects;
  private final RoadmapRepository roadmaps;
  private final RoadmapPhaseRepository phases;

  public RoadmapService(
      ProjectService projects, RoadmapRepository roadmaps, RoadmapPhaseRepository phases) {
    this.projects = projects;
    this.roadmaps = roadmaps;
    this.phases = phases;
  }

  /** Creates the roadmap, or returns the existing one. A project has exactly one. */
  public Roadmap createOrGet(UUID projectId) {
    projects.requireWritable(projectId);
    return roadmaps.findByProjectId(projectId).orElseGet(() -> roadmaps.save(new Roadmap(projectId)));
  }

  @Transactional(readOnly = true)
  public Roadmap require(UUID projectId) {
    projects.requireReadable(projectId);
    return roadmaps
        .findByProjectId(projectId)
        .orElseThrow(
            () -> new ResourceNotFoundException("This project has no roadmap yet: " + projectId));
  }

  @Transactional(readOnly = true)
  public List<RoadmapPhase> listPhases(UUID projectId) {
    projects.requireReadable(projectId);
    return roadmaps
        .findByProjectId(projectId)
        .map(roadmap -> phases.findByRoadmapIdOrderByPosition(roadmap.getId()))
        .orElseGet(List::of);
  }

  public RoadmapPhase addPhase(UUID projectId, int position, String title, String description) {
    Roadmap roadmap = createOrGet(projectId);
    if (position < 1) {
      throw new DomainRuleException("Phase position starts at 1");
    }
    if (phases.existsByRoadmapIdAndPosition(roadmap.getId(), position)) {
      throw new DomainRuleException("Phase position " + position + " is already taken");
    }
    RoadmapPhase added = phases.save(new RoadmapPhase(roadmap.getId(), position, title, description));
    // The plan now has a phase it did not have, which is a change to its shape. Recorded after the
    // save so that a rejected position leaves the roadmap's instant untouched.
    roadmap.recordStructuralChange();
    return added;
  }

  @Transactional(readOnly = true)
  public RoadmapPhase requirePhase(UUID projectId, UUID phaseId) {
    RoadmapPhase phase =
        phases
            .findById(phaseId)
            .orElseThrow(() -> new ResourceNotFoundException("Phase not found: " + phaseId));
    if (!phase.getRoadmapId().equals(require(projectId).getId())) {
      throw new DomainRuleException("This phase belongs to another project");
    }
    return phase;
  }

  /**
   * Moves a phase to a new position, shifting the phases in between.
   *
   * <p>Positions are rewritten as a contiguous 1..n sequence rather than patched in place, so no
   * reorder can leave a gap or a duplicate behind.
   *
   * <p>Asking for the position a phase already holds is a successful no-op, not a client error: the
   * caller wanted an order and got it, and refusing would be hostile to someone doing nothing wrong.
   * It returns before any mutation, so neither the roadmap's instant nor the phase's moves — see the
   * guard below for why that matters more than it looks.
   */
  public List<RoadmapPhase> movePhase(UUID projectId, UUID phaseId, int newPosition) {
    projects.requireWritable(projectId);
    RoadmapPhase phase = requirePhase(projectId, phaseId);
    if (phase.getPosition() == newPosition) {
      // Nothing changed, so nothing may be recorded as having changed. Without this the renumbering
      // below would rewrite every position to the value it already had and then stamp the roadmap,
      // making the plan look freshly observed to every later context pack — the false freshness
      // ContextProvenance exists to prevent. Before any mutation rather than after, because
      // RoadmapPhase.moveTo stamps unconditionally: returning later would close the roadmap's side
      // and leave the same lie leaking through the phase rows. The position needs no range check
      // here — it is one a phase currently holds, so it is in range by construction.
      return phases.findByRoadmapIdOrderByPosition(phase.getRoadmapId());
    }
    Roadmap roadmap = require(projectId);
    List<RoadmapPhase> ordered =
        new java.util.ArrayList<>(phases.findByRoadmapIdOrderByPosition(phase.getRoadmapId()));
    if (newPosition < 1 || newPosition > ordered.size()) {
      throw new DomainRuleException(
          "Position must be between 1 and " + ordered.size() + " for this roadmap");
    }

    ordered.removeIf(candidate -> candidate.getId().equals(phaseId));
    ordered.add(newPosition - 1, phase);
    // Two passes: park every phase outside the used range first, because positions are unique per
    // roadmap and a direct rewrite would collide with a row that has not been renumbered yet.
    int parking = ordered.size() + 1;
    for (RoadmapPhase current : ordered) {
      current.moveTo(parking++);
    }
    phases.flush();
    int sequence = 1;
    for (RoadmapPhase current : ordered) {
      current.moveTo(sequence++);
    }
    phases.flush();
    // The order of the phases is the shape of the plan, so a reorder is structural even though no
    // phase was added or lost. Recorded after both renumbering passes have succeeded.
    roadmap.recordStructuralChange();
    return ordered;
  }
}
