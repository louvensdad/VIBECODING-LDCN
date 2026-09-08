package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.state.application.ProjectStateService;
import com.vibecode.task.domain.Task;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The task the work is actually about.
 *
 * <p>Two candidates come out of one row, because the row says two different things. The objective
 * is what the task is for; the status and risk are where it stands. A reader needs the first to know
 * what to do and the second to know what has already happened, and a budget that can only afford one
 * of them should be able to choose.
 *
 * <p>{@code ContextKind} names {@code CURRENT_TASK} as a producer of {@code CONSTRAINT} as well as
 * {@code OBJECTIVE}, and this collector emits no constraint. That is honest rather than incomplete:
 * the task row has no field holding a constraint. What binds the work is written down as acceptance
 * criteria, and those are collected under {@link ContextSourceType#ACCEPTANCE_CRITERIA} from their
 * own rows. Splitting the objective text into an objective and some inferred constraints would be
 * this collector inventing a distinction the record never made.
 *
 * <p>A project with nothing left to do yields nothing.
 */
@Component
@Transactional(readOnly = true)
public class CurrentTaskContextCollector implements ContextCollector {

  private final ProjectStateService state;

  public CurrentTaskContextCollector(ProjectStateService state) {
    this.state = state;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.CURRENT_TASK;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // ProjectStateService.of authorizes the project before computing anything.
    Task task = state.of(projectId).currentTask();
    if (task == null) {
      return List.of();
    }

    ContextSource source =
        ContextSource.of(ContextSourceType.CURRENT_TASK, task.getId().toString());
    ContextProvenance provenance =
        new ContextProvenance(source, projectId, task.getUpdatedAt());

    return List.of(
        new ContextItem(
            "current-task:" + task.getId() + ":objective",
            ContextKind.OBJECTIVE,
            task.getTitle(),
            task.getObjective(),
            provenance),
        new ContextItem(
            "current-task:" + task.getId() + ":status",
            ContextKind.CURRENT_STATE,
            "Current task status",
            "Task: "
                + task.getTitle()
                + "\nStatus: "
                + task.getStatus()
                + "\nRisk level: "
                + task.getRiskLevel(),
            provenance));
  }
}
