package com.vibecode.task.application;

import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.domain.OutputAnalysisStatus;
import com.vibecode.task.domain.CriterionStatus;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskAcceptanceCriterion;
import com.vibecode.task.domain.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single place that decides whether a task may be called finished.
 *
 * <p>Four conditions, all required:
 *
 * <ol>
 *   <li>every mandatory dependency is completed;
 *   <li>the most recent evidence is not a failure or a blocker;
 *   <li>every required acceptance criterion is satisfied;
 *   <li>there is evidence of success behind it.
 * </ol>
 *
 * <p>The policy reports <em>why</em> a task cannot close, not just that it cannot. Those reasons
 * become the blocking issues the Next Step Engine shows the user, so the platform never says "not
 * yet" without saying what is missing.
 */
@Component
public class TaskCompletionPolicy {

  /** The verdict, with the specific reasons a task is not closable. */
  public record CompletionAssessment(boolean canComplete, List<String> missing) {

    public CompletionAssessment {
      missing = List.copyOf(missing);
    }

    static CompletionAssessment allowed() {
      return new CompletionAssessment(true, List.of());
    }

    static CompletionAssessment blocked(List<String> missing) {
      return new CompletionAssessment(false, missing);
    }
  }

  public CompletionAssessment evaluate(
      List<Task> mandatoryDependencies,
      List<TaskAcceptanceCriterion> criteria,
      Optional<OutputAnalysisRecord> latestAnalysis) {

    List<String> missing = new ArrayList<>();

    List<String> unfinishedDependencies =
        mandatoryDependencies.stream()
            .filter(dependency -> dependency.getStatus() != TaskStatus.COMPLETED)
            .map(Task::getTitle)
            .toList();
    if (!unfinishedDependencies.isEmpty()) {
      missing.add("Dependências não concluídas: " + String.join(", ", unfinishedDependencies));
    }

    List<String> unsatisfiedCriteria =
        criteria.stream()
            .filter(TaskAcceptanceCriterion::isRequired)
            .filter(criterion -> criterion.getStatus() != CriterionStatus.SATISFIED)
            .map(TaskAcceptanceCriterion::getDescription)
            .toList();
    if (!unsatisfiedCriteria.isEmpty()) {
      missing.add(
          "Critérios de aceite obrigatórios pendentes: " + String.join("; ", unsatisfiedCriteria));
    }

    if (latestAnalysis.isEmpty()) {
      missing.add("Nenhuma evidência foi registrada para esta tarefa.");
    } else {
      OutputAnalysisStatus status = latestAnalysis.get().getStatus();
      if (status == OutputAnalysisStatus.BLOCKED) {
        missing.add("A evidência mais recente indica um bloqueio externo não resolvido.");
      } else if (status == OutputAnalysisStatus.FAILURE || status == OutputAnalysisStatus.PARTIAL) {
        missing.add("A evidência mais recente contém falha técnica não corrigida.");
      } else if (status != OutputAnalysisStatus.SUCCESS) {
        missing.add("A evidência mais recente não comprova sucesso técnico.");
      }
    }

    return missing.isEmpty() ? CompletionAssessment.allowed() : CompletionAssessment.blocked(missing);
  }
}
