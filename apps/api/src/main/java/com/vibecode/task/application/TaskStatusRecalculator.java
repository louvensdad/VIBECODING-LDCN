package com.vibecode.task.application;

import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskDependency;
import com.vibecode.task.domain.TaskStatus;
import com.vibecode.task.infrastructure.TaskDependencyRepository;
import com.vibecode.task.infrastructure.TaskRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes what is workable after anything in the plan changes.
 *
 * <p>Readiness is derived, never typed in: a task is READY exactly when all its dependencies are
 * completed. Statuses the user is holding — in progress, blocked, awaiting validation — and
 * finished tasks are left untouched, which is what keeps a recalculation from erasing the fact that
 * work is underway.
 */
@Component
public class TaskStatusRecalculator {

  private final TaskRepository tasks;
  private final TaskDependencyRepository dependencies;
  private final RoadmapService roadmaps;
  private final PhaseStatusCalculator phaseStatus;

  public TaskStatusRecalculator(
      TaskRepository tasks,
      TaskDependencyRepository dependencies,
      RoadmapService roadmaps,
      PhaseStatusCalculator phaseStatus) {
    this.tasks = tasks;
    this.dependencies = dependencies;
    this.roadmaps = roadmaps;
    this.phaseStatus = phaseStatus;
  }

  @Transactional
  public void recalculate(UUID projectId) {
    List<Task> all = tasks.findByProjectId(projectId);
    Map<UUID, Task> byId = all.stream().collect(Collectors.toMap(Task::getId, Function.identity()));

    for (Task task : all) {
      task.applyReadiness(dependenciesSatisfied(task, byId));
    }

    for (RoadmapPhase phase : roadmaps.listPhases(projectId)) {
      List<Task> phaseTasks =
          all.stream()
              .filter(task -> task.getPhaseId().equals(phase.getId()))
              .sorted(java.util.Comparator.comparingInt(Task::getPosition))
              .toList();
      phase.applyDerivedStatus(phaseStatus.derive(phaseTasks));
    }
  }

  private boolean dependenciesSatisfied(Task task, Map<UUID, Task> byId) {
    List<TaskDependency> taskDependencies = dependencies.findByTaskId(task.getId());
    return taskDependencies.stream()
        .allMatch(
            dependency -> {
              Task target = byId.get(dependency.getDependencyTaskId());
              // A dependency pointing outside this project's task set is treated as unsatisfied
              // rather than ignored: silently dropping it would make a task look workable.
              return target != null && target.getStatus() == TaskStatus.COMPLETED;
            });
  }
}
