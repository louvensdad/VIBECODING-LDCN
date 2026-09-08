package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.infrastructure.OutputAnalysisRecordRepository;
import com.vibecode.state.application.ProjectStateService;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskStatus;
import java.util.ArrayList;
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
 * <p><b>Neither half is windowed.</b> {@link ContextReadWindow} bounds sources that grow with how
 * fast a machine produces output; an open problem is not one of those. How many blocked tasks a
 * project has is bounded by its plan, and how many runs are awaiting correction is a fact about the
 * project — five hundred of them is a real signal, not noise to be capped. Reading these through the
 * evidence window would have been worse than a cap: a verdict that fell out of the window would
 * leave {@code ACTIVE_ERRORS} and {@code LATEST_OUTPUT_ANALYSIS} at the same moment, since both read
 * the same evidence rows, so the failure would survive nowhere at all. That is content dying in a
 * collector, which is the one outcome this whole layer exists to prevent — and it is the difference
 * from the brain {@code ERROR} case above, where only the claim is withheld and the content lives on.
 *
 * <p>This collector queries {@code OutputAnalysisRecordRepository} directly, by project id. That
 * query would answer for anyone, so what scopes it to the caller is the {@code
 * ProjectStateService.of} call earlier in the same method, which calls {@code requireReadable}
 * before it computes anything. Removing that call removes the only ownership check on this read.
 */
@Component
@Transactional(readOnly = true)
public class ActiveErrorsContextCollector implements ContextCollector {

  private final ProjectStateService state;
  private final OutputAnalysisRecordRepository analyses;

  public ActiveErrorsContextCollector(
      ProjectStateService state, OutputAnalysisRecordRepository analyses) {
    this.state = state;
    this.analyses = analyses;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.ACTIVE_ERRORS;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // This is the ownership check for both reads below, including the repository query, which
    // takes a project id and would otherwise answer for anyone.
    List<Task> tasks = state.of(projectId).allTasks();

    List<ContextItem> items = new ArrayList<>();
    tasks.stream()
        .filter(task -> task.getStatus() == TaskStatus.BLOCKED)
        // No tiebreak needed, and none is pretended: listOrdered sorts by phase position then task
        // position, and V3 declares UNIQUE(phase_id, position), so those two keys are already a
        // total order. If that constraint is ever relaxed, this becomes non-deterministic and an
        // explicit tiebreak has to be added here.
        .map(task -> blockedTask(projectId, task))
        .forEach(items::add);

    // Ordered and filtered in the database; see the repository method for why it is unbounded.
    analyses.findRequiringCorrectionByProjectId(projectId).stream()
        .map(analysis -> failingRun(projectId, analysis))
        .forEach(items::add);

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
