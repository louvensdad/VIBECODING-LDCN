package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.project.application.ProjectService;
import com.vibecode.project.domain.Project;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.state.application.ProjectStateService;
import com.vibecode.state.domain.ProjectState;
import com.vibecode.task.domain.Task;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where the project stands, as the system works it out — not as anyone remembers it.
 *
 * <p>This is the computed counterpart of a brain entry of kind {@code CURRENT_STATE}. Both may end
 * up in the same pack and that is deliberate: remembered state and computed state disagreeing is
 * information, and a collector that reconciled them would be deciding which one is true.
 *
 * <p>The figures are recomputed by {@link ProjectStateService} rather than stored, so nothing here
 * can drift out of step with the tasks it summarizes.
 *
 * <p><b>The state's own list of active problems is not emitted here.</b> It is prose assembled from
 * blocked tasks and a failing analysis, with no identifier pointing back at either, so an item built
 * from it could not name the record it came from. Those problems are collected under {@link
 * ContextSourceType#ACTIVE_ERRORS} instead, one candidate per real row, each traceable.
 *
 * <p><b>On the timestamp.</b> The computed state is not a row and has no timestamp of its own. Using
 * the moment of computation would date every collection at "now", which both hides staleness and
 * makes two collections of unchanged data differ. So the items are dated at the most recent change
 * among the records the state was computed from.
 */
@Component
@Transactional(readOnly = true)
public class CurrentStateContextCollector implements ContextCollector {

  private final ProjectService projects;
  private final ProjectStateService state;
  private final RoadmapService roadmaps;

  public CurrentStateContextCollector(
      ProjectService projects, ProjectStateService state, RoadmapService roadmaps) {
    this.projects = projects;
    this.state = state;
    this.roadmaps = roadmaps;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.CURRENT_STATE;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // Both calls authorize; requireReadable is what makes another user's project a not-found here
    // rather than an empty state.
    Project project = projects.requireReadable(projectId);
    ProjectState current = state.of(projectId);
    // Read for their timestamps only. ProjectState carries the phases that have no tasks by
    // title alone, and the item's content is derived from all of them, so the fold behind
    // recordedAt needs the rows themselves.
    List<RoadmapPhase> phases = roadmaps.listPhases(projectId);

    // The computed state has no row of its own, so the project it was computed for is the
    // addressable record behind it. Unversioned: there is nothing to version.
    ContextSource source =
        ContextSource.of(ContextSourceType.CURRENT_STATE, projectId.toString());
    ContextProvenance provenance =
        new ContextProvenance(source, projectId, observedAt(project, current, phases));

    List<ContextItem> items = new ArrayList<>();
    items.add(
        new ContextItem(
            "state:" + projectId + ":progress",
            ContextKind.CURRENT_STATE,
            "Computed project state",
            "Progress: "
                + current.progressPercentage()
                + "% ("
                + current.completedTasks()
                + " of "
                + current.totalTasks()
                + " tasks complete)"
                + "\nBlocked tasks: "
                + current.blockedTasks()
                + "\nPlan recorded: "
                + (current.hasPlan() ? "yes" : "no"),
            provenance));

    if (!current.nextCandidateTasks().isEmpty()) {
      items.add(
          new ContextItem(
              "state:" + projectId + ":ready-tasks",
              ContextKind.NEXT_STEP,
              "Tasks ready to start",
              current.nextCandidateTasks().stream()
                  .map(Task::getTitle)
                  .collect(Collectors.joining("\n")),
              provenance));
    }

    if (!current.phasesWithoutTasks().isEmpty()) {
      items.add(
          new ContextItem(
              "state:" + projectId + ":phases-without-tasks",
              ContextKind.NEXT_STEP,
              "Phases not yet broken into tasks",
              String.join("\n", current.phasesWithoutTasks()),
              provenance));
    }

    return List.copyOf(items);
  }

  /**
   * The newest change among every record the state was computed from.
   *
   * <p><b>Every phase, not just the current one.</b> {@code phasesWithoutTasks} is derived from all
   * of them, so a phase nobody has broken down yet is an input to the content whether or not the
   * work happens to be in it. Folding only the current phase left an item whose text could change
   * while its provenance swore nothing had been observed to change — the exact staleness blindness
   * this method exists to prevent.
   */
  private Instant observedAt(Project project, ProjectState current, List<RoadmapPhase> phases) {
    Instant latestTask =
        current.allTasks().stream().map(Task::getUpdatedAt).max(Instant::compareTo).orElse(null);
    Instant latestPhase =
        phases.stream().map(RoadmapPhase::getUpdatedAt).max(Instant::compareTo).orElse(null);
    return SourceObservation.latestOf(
        project.getUpdatedAt(),
        latestTask,
        latestPhase,
        current.lastEvidence() == null ? null : current.lastEvidence().getCreatedAt(),
        current.lastAnalysis() == null ? null : current.lastAnalysis().getCreatedAt());
  }
}
