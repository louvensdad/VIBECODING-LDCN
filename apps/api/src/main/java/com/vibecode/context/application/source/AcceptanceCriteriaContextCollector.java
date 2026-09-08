package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.state.application.ProjectStateService;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskAcceptanceCriterion;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What "done" means for the current task: every acceptance criterion written for it.
 *
 * <p>Satisfied and rejected criteria are collected alongside pending ones. A criterion that has
 * already been decided is still part of the definition of done, and a reader who sees only the
 * pending ones would think the bar is lower than it is. Emitting only what is outstanding would also
 * be a selection rule hidden in a collector.
 *
 * <p>Required and optional are both carried, stated in the item rather than used to choose. Whether
 * an optional criterion is worth its tokens is a budget question, and the budget step needs to see
 * the criterion to answer it.
 *
 * <p><b>A criterion has no creation timestamp of its own.</b> The row records only when it was
 * decided, and nothing while it is pending. So {@code recordedAt} is the decision instant where
 * there is one and the owning task's creation instant otherwise — the earliest moment the criterion
 * can be known to have existed. That is weaker than the other sources and is stated here rather than
 * papered over; a criterion is never dated at "now", which would make it look freshly observed on
 * every collection and would make collection non-deterministic.
 */
@Component
@Transactional(readOnly = true)
public class AcceptanceCriteriaContextCollector implements ContextCollector {

  private final ProjectStateService state;
  private final TaskService tasks;

  public AcceptanceCriteriaContextCollector(ProjectStateService state, TaskService tasks) {
    this.state = state;
    this.tasks = tasks;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.ACCEPTANCE_CRITERIA;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // ProjectStateService.of authorizes the project; the criteria are then read for a task that
    // came out of that authorized read, so they cannot belong to anyone else.
    Task task = state.of(projectId).currentTask();
    if (task == null) {
      return List.of();
    }

    return tasks.criteriaOf(task.getId()).stream()
        // The repository returns criteria unordered. Sorting by id gives a stable sequence that
        // does not depend on the database's whim, and does not imply any priority among them.
        .sorted(Comparator.comparing(criterion -> criterion.getId().toString()))
        .map(criterion -> toCandidate(projectId, task, criterion))
        .toList();
  }

  private ContextItem toCandidate(
      UUID projectId, Task task, TaskAcceptanceCriterion criterion) {
    String content =
        criterion.getDescription()
            + "\nRequired: "
            + (criterion.isRequired() ? "yes" : "no")
            + "\nStatus: "
            + criterion.getStatus();

    ContextSource source =
        ContextSource.of(ContextSourceType.ACCEPTANCE_CRITERIA, criterion.getId().toString());
    return new ContextItem(
        "criterion:" + criterion.getId(),
        ContextKind.CONSTRAINT,
        criterion.isRequired() ? "Acceptance criterion (required)" : "Acceptance criterion",
        content,
        new ContextProvenance(
            source,
            projectId,
            criterion.getDecidedAt() == null ? task.getCreatedAt() : criterion.getDecidedAt()));
  }
}
