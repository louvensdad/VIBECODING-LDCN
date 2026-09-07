package com.vibecode.task.application;

import com.vibecode.roadmap.domain.PhaseStatus;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskStatus;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Derives a phase's status from the tasks inside it.
 *
 * <p>Phase status is never stored independently of its tasks, so the roadmap cannot claim a phase
 * is done while a task in it is still open.
 */
@Component
public class PhaseStatusCalculator {

  public PhaseStatus derive(List<Task> phaseTasks) {
    if (phaseTasks.isEmpty()) {
      return PhaseStatus.PLANNED;
    }
    if (phaseTasks.stream().allMatch(task -> task.getStatus().isFinished())) {
      return PhaseStatus.COMPLETED;
    }
    if (anyIs(phaseTasks, TaskStatus.BLOCKED)) {
      return PhaseStatus.BLOCKED;
    }
    if (anyIs(phaseTasks, TaskStatus.IN_PROGRESS) || anyIs(phaseTasks, TaskStatus.NEEDS_VALIDATION)) {
      return PhaseStatus.IN_PROGRESS;
    }
    // Work already finished in a phase that still has open tasks means the phase is underway.
    if (phaseTasks.stream().anyMatch(task -> task.getStatus().isFinished())) {
      return PhaseStatus.IN_PROGRESS;
    }
    if (anyIs(phaseTasks, TaskStatus.READY)) {
      return PhaseStatus.READY;
    }
    return PhaseStatus.PLANNED;
  }

  private boolean anyIs(List<Task> tasks, TaskStatus status) {
    return tasks.stream().anyMatch(task -> task.getStatus() == status);
  }
}
