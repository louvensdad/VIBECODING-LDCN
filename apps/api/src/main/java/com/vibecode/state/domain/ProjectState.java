package com.vibecode.state.domain;

import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.domain.TaskEvidence;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.task.domain.Task;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A consolidated read of where a project stands.
 *
 * <p>Assembled on every request from roadmap, tasks and evidence. Nothing here is stored — in
 * particular {@link #progressPercentage()} is computed, so it cannot disagree with the tasks it
 * summarizes.
 */
public record ProjectState(
    UUID projectId,
    RoadmapPhase currentPhase,
    Task currentTask,
    List<Task> allTasks,
    int completedTasks,
    int totalTasks,
    int blockedTasks,
    List<String> activeProblems,
    TaskEvidence lastEvidence,
    OutputAnalysisRecord lastAnalysis,
    List<Task> nextCandidateTasks,
    /** Phases that exist in the plan but have no tasks yet: planned work not yet broken down. */
    List<String> phasesWithoutTasks) {

  public ProjectState {
    allTasks = List.copyOf(allTasks);
    activeProblems = List.copyOf(activeProblems);
    nextCandidateTasks = List.copyOf(nextCandidateTasks);
    phasesWithoutTasks = List.copyOf(phasesWithoutTasks);
  }

  /** Derived, never persisted. */
  public int progressPercentage() {
    return totalTasks == 0 ? 0 : Math.round(completedTasks * 100f / totalTasks);
  }

  public boolean hasPlan() {
    return totalTasks > 0;
  }

  /**
   * A project is only complete when every task is finished <em>and</em> no phase is still waiting
   * to be broken into tasks. An empty phase is planned work, not finished work.
   */
  public boolean isComplete() {
    return totalTasks > 0 && completedTasks == totalTasks && phasesWithoutTasks.isEmpty();
  }

  /** All work done, but the plan still has phases nobody has detailed. */
  public boolean isPlanIncomplete() {
    return totalTasks > 0 && completedTasks == totalTasks && !phasesWithoutTasks.isEmpty();
  }

  public Optional<Task> currentTaskOptional() {
    return Optional.ofNullable(currentTask);
  }

  public Optional<OutputAnalysisRecord> lastAnalysisOptional() {
    return Optional.ofNullable(lastAnalysis);
  }
}
