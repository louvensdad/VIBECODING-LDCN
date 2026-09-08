package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.domain.TaskEvidence;
import com.vibecode.output.infrastructure.OutputAnalysisRecordRepository;
import com.vibecode.state.application.ProjectStateService;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Problems the project is known to still have.
 *
 * <p><b>What counts as active, and why.</b> No table records "open problems", and this task creates
 * none. So the source is defined out of two records that already carry their own openness, checkably
 * and without inference:
 *
 * <ul>
 *   <li>a task whose status is {@code BLOCKED} — the status is the project's own statement that the
 *       work cannot proceed, and it stays until someone moves it;
 *   <li>an output analysis whose {@code requiresCorrection} flag is set — the analyzer's own verdict
 *       that the run needs fixing, stored on the row at the moment it was judged.
 * </ul>
 *
 * <p>Both conditions read a flag the record wrote about itself. That is what makes this a definition
 * of the source rather than a filter: every record that satisfies it is emitted, none is ranked, and
 * nothing is dropped for being old or repetitive.
 *
 * <p><b>Brain entries of type {@code ERROR} are deliberately not re-emitted here.</b> The brain is
 * append-only and has no resolved marker, so calling a remembered error "active" would be a claim
 * the record cannot support — the fix may have been made and written down as a {@code SOLUTION}
 * entry a minute later. Nothing is lost by the omission: every such entry is already collected in
 * full by the brain collector, as {@code sourceType=BRAIN_ENTRY, kind=ERROR}. It is the {@code
 * ACTIVE_ERRORS} claim that is withheld, not the content.
 *
 * <p>Analyses are read through the same bounded window as the evidence they judge; see {@link
 * ContextReadWindow}. Blocked tasks are not windowed — a project's task list is bounded by its plan.
 */
@Component
@Transactional(readOnly = true)
public class ActiveErrorsContextCollector implements ContextCollector {

  private final ProjectStateService state;
  private final EvidenceService evidence;
  private final OutputAnalysisRecordRepository analyses;

  public ActiveErrorsContextCollector(
      ProjectStateService state,
      EvidenceService evidence,
      OutputAnalysisRecordRepository analyses) {
    this.state = state;
    this.evidence = evidence;
    this.analyses = analyses;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.ACTIVE_ERRORS;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // Both reads authorize the project first: ProjectStateService.of and
    // EvidenceService.listRecentForProject each call requireReadable before querying.
    List<Task> tasks = state.of(projectId).allTasks();
    List<UUID> evidenceIds =
        evidence.listRecentForProject(projectId, window.recentRecords()).stream()
            .map(TaskEvidence::getId)
            .toList();

    List<ContextItem> items = new ArrayList<>();
    tasks.stream()
        .filter(task -> task.getStatus() == TaskStatus.BLOCKED)
        // allTasks is already in plan order, which is stable; the id keeps ties total.
        .map(task -> blockedTask(projectId, task))
        .forEach(items::add);

    if (!evidenceIds.isEmpty()) {
      analyses.findByEvidenceIdIn(evidenceIds).stream()
          .filter(OutputAnalysisRecord::isRequiresCorrection)
          .sorted(
              Comparator.comparing(OutputAnalysisRecord::getCreatedAt)
                  .reversed()
                  .thenComparing(analysis -> analysis.getId().toString()))
          .map(analysis -> failingRun(projectId, analysis))
          .forEach(items::add);
    }
    return List.copyOf(items);
  }

  private ContextItem blockedTask(UUID projectId, Task task) {
    ContextSource source =
        ContextSource.of(ContextSourceType.ACTIVE_ERRORS, task.getId().toString());
    return new ContextItem(
        "active-error:task:" + task.getId(),
        ContextKind.ERROR,
        "Blocked task",
        "Task blocked: " + task.getTitle() + "\nObjective: " + task.getObjective(),
        new ContextProvenance(source, projectId, task.getUpdatedAt()));
  }

  private ContextItem failingRun(UUID projectId, OutputAnalysisRecord analysis) {
    String signals =
        analysis.getSignalNames().isEmpty()
            ? "none recorded"
            : String.join(", ", analysis.getSignalNames());
    ContextSource source =
        ContextSource.of(ContextSourceType.ACTIVE_ERRORS, analysis.getId().toString());
    return new ContextItem(
        "active-error:analysis:" + analysis.getId(),
        ContextKind.ERROR,
        "Run requiring correction: " + analysis.getStatus(),
        analysis.getSummary() + "\nSignals: " + signals,
        new ContextProvenance(source, projectId, analysis.getCreatedAt()));
  }
}
