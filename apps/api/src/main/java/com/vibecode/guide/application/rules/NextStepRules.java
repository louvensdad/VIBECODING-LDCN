package com.vibecode.guide.application.rules;

import com.vibecode.guide.domain.NextStepContext;
import com.vibecode.guide.domain.NextStepPriority;
import com.vibecode.guide.domain.NextStepRecommendation;
import com.vibecode.guide.domain.NextStepRule;
import com.vibecode.guide.domain.NextStepType;
import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.domain.OutputAnalysisStatus;
import com.vibecode.prompt.domain.PromptType;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskStatus;
import java.util.List;
import java.util.Optional;

/**
 * The deterministic rules, in the order the engine applies them. First match wins.
 *
 * <p>The order encodes the product's priorities: nothing new starts while something is broken, and
 * nothing is called done without evidence.
 */
public final class NextStepRules {

  private NextStepRules() {}

  /** The ordered rule set. Order is the policy; changing it changes the product's behaviour. */
  public static List<NextStepRule> ordered() {
    return List.of(
        new NoPlanRule(),
        new PlanNotDetailedRule(),
        new BlockedEvidenceRule(),
        new FailedEvidenceRule(),
        new CriticalSecurityFindingRule(),
        new ProjectCompleteRule(),
        new AwaitingValidationRule(),
        new ContinueInProgressRule(),
        new CriticalRiskReviewRule(),
        new StartNextReadyTaskRule(),
        new DependenciesPendingRule());
  }

  /** REGRA G — nothing to navigate yet. */
  static final class NoPlanRule implements NextStepRule {

    @Override
    public Optional<NextStepRecommendation> evaluate(NextStepContext context) {
      if (context.state().hasPlan()) {
        return Optional.empty();
      }
      return Optional.of(
          new NextStepRecommendation(
              NextStepType.WAIT_FOR_USER,
              "Estruturar o roadmap do projeto",
              "O projeto ainda não possui fases e tarefas, então não há passo seguinte a calcular.",
              null,
              NextStepPriority.HIGH,
              List.of(),
              List.of(
                  "Criar o roadmap do projeto",
                  "Adicionar as fases principais",
                  "Adicionar as primeiras tarefas com critérios de aceite"),
              PromptType.ASK_FOR_EVIDENCE));
    }

    @Override
    public String name() {
      return "no-plan";
    }
  }

  /**
   * Every task is done, but phases in the plan still have no tasks.
   *
   * <p>An empty phase is planned work nobody has broken down yet. Reporting the project complete
   * here would tell the user they are finished while the plan still says otherwise.
   */
  static final class PlanNotDetailedRule implements NextStepRule {

    @Override
    public Optional<NextStepRecommendation> evaluate(NextStepContext context) {
      if (!context.state().isPlanIncomplete()) {
        return Optional.empty();
      }
      List<String> pending = context.state().phasesWithoutTasks();
      return Optional.of(
          new NextStepRecommendation(
              NextStepType.WAIT_FOR_USER,
              "Detalhar as fases restantes do roadmap",
              "Todas as tarefas existentes foram concluídas, mas "
                  + pending.size()
                  + " fase(s) ainda não têm nenhuma tarefa. O projeto não está concluído.",
              null,
              NextStepPriority.HIGH,
              pending,
              List.of(
                  "Adicionar tarefas às fases pendentes",
                  "Definir critérios de aceite para cada uma"),
              PromptType.ASK_FOR_EVIDENCE));
    }

    @Override
    public String name() {
      return "plan-not-detailed";
    }
  }

  /** REGRA H — everything is done. No task is invented to fill the gap. */
  static final class ProjectCompleteRule implements NextStepRule {

    @Override
    public Optional<NextStepRecommendation> evaluate(NextStepContext context) {
      if (!context.state().isComplete()) {
        return Optional.empty();
      }
      return Optional.of(
          new NextStepRecommendation(
              NextStepType.PROJECT_COMPLETE,
              "Projeto concluído",
              "Todas as tarefas do roadmap foram concluídas com evidência registrada.",
              null,
              NextStepPriority.LOW,
              List.of(),
              List.of("Revisar o roadmap se novas fases forem necessárias"),
              PromptType.MODEL_HANDOFF));
    }

    @Override
    public String name() {
      return "project-complete";
    }
  }

  /** REGRA B — an external obstacle outranks everything else. */
  static final class BlockedEvidenceRule implements NextStepRule {

    @Override
    public Optional<NextStepRecommendation> evaluate(NextStepContext context) {
      Optional<Task> task = context.currentTask();
      boolean blockedByEvidence =
          context.latestAnalysis().map(OutputAnalysisRecord::getStatus).orElse(null)
              == OutputAnalysisStatus.BLOCKED;
      boolean blockedByStatus = task.map(Task::getStatus).orElse(null) == TaskStatus.BLOCKED;
      if (task.isEmpty() || !(blockedByEvidence || blockedByStatus)) {
        return Optional.empty();
      }
      return Optional.of(
          new NextStepRecommendation(
              NextStepType.RESOLVE_BLOCKER,
              "Resolver o bloqueio de: " + task.get().getTitle(),
              "A evidência mais recente indica um obstáculo externo — permissão, credencial ou "
                  + "limite — que nenhuma alteração de código resolve.",
              task.get().getId(),
              NextStepPriority.CRITICAL,
              context.latestAnalysis().map(analysis -> List.of(analysis.getSummary())).orElse(List.of()),
              List.of(
                  "Identificar o recurso ou permissão que falta",
                  "Restabelecer o acesso",
                  "Repetir o comando e registrar a nova saída"),
              PromptType.RESOLVE_BLOCKER));
    }

    @Override
    public String name() {
      return "blocked-evidence";
    }
  }

  /** REGRA A — a failure stops the line. No new feature is recommended over a broken one. */
  static final class FailedEvidenceRule implements NextStepRule {

    @Override
    public Optional<NextStepRecommendation> evaluate(NextStepContext context) {
      Optional<Task> task = context.currentTask();
      OutputAnalysisStatus status =
          context.latestAnalysis().map(OutputAnalysisRecord::getStatus).orElse(null);
      if (task.isEmpty()
          || !(status == OutputAnalysisStatus.FAILURE || status == OutputAnalysisStatus.PARTIAL)) {
        return Optional.empty();
      }
      OutputAnalysisRecord analysis = context.latestAnalysis().orElseThrow();
      return Optional.of(
          new NextStepRecommendation(
              NextStepType.FIX_ERROR,
              "Corrigir o erro em: " + task.get().getTitle(),
              "A última evidência registrada contém falha técnica ("
                  + String.join(", ", analysis.getSignalNames())
                  + "). Nenhuma nova funcionalidade deve começar antes da correção.",
              task.get().getId(),
              NextStepPriority.CRITICAL,
              List.of(analysis.getSummary()),
              List.of(
                  "Investigar a causa raiz do erro",
                  "Aplicar somente a correção necessária",
                  "Executar novamente o comando que falhou",
                  "Registrar a nova saída como evidência"),
              PromptType.FIX_ERROR));
    }

    @Override
    public String name() {
      return "failed-evidence";
    }
  }

  /**
   * REGRA C — the work is claimed done but the evidence does not close it. This is also where a task
   * whose acceptance criteria are still open lands.
   */
  static final class AwaitingValidationRule implements NextStepRule {

    @Override
    public Optional<NextStepRecommendation> evaluate(NextStepContext context) {
      Optional<Task> task = context.currentTask();
      boolean needsValidation =
          task.map(Task::getStatus).orElse(null) == TaskStatus.NEEDS_VALIDATION
              || context.latestAnalysis().map(OutputAnalysisRecord::getStatus).orElse(null)
                  == OutputAnalysisStatus.NEEDS_VALIDATION;
      if (task.isEmpty() || !needsValidation) {
        return Optional.empty();
      }
      List<String> missing =
          context.currentTaskCompletion().map(assessment -> assessment.missing()).orElse(List.of());
      return Optional.of(
          new NextStepRecommendation(
              NextStepType.VALIDATE_RESULT,
              "Validar o resultado de: " + task.get().getTitle(),
              "A tarefa foi reportada como pronta, mas ainda falta evidência ou critério para "
                  + "considerá-la concluída.",
              task.get().getId(),
              NextStepPriority.HIGH,
              missing,
              List.of(
                  "Executar build e testes relacionados",
                  "Registrar a saída real como evidência",
                  "Confirmar explicitamente cada critério de aceite obrigatório"),
              PromptType.VALIDATE_RESULT));
    }

    @Override
    public String name() {
      return "awaiting-validation";
    }
  }

  /** REGRA D (parte 1) — work already underway is finished before anything else is started. */
  static final class ContinueInProgressRule implements NextStepRule {

    @Override
    public Optional<NextStepRecommendation> evaluate(NextStepContext context) {
      Optional<Task> task =
          context.currentTask().filter(current -> current.getStatus() == TaskStatus.IN_PROGRESS);
      if (task.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new NextStepRecommendation(
              NextStepType.CONTINUE_TASK,
              "Continuar: " + task.get().getTitle(),
              "Esta tarefa já está em andamento e ainda não tem evidência de conclusão.",
              task.get().getId(),
              NextStepPriority.MEDIUM,
              List.of(),
              List.of(
                  "Concluir a implementação da tarefa atual",
                  "Executar build e testes",
                  "Registrar a saída como evidência"),
              PromptType.CONTINUE_TASK));
    }

    @Override
    public String name() {
      return "continue-in-progress";
    }
  }

  /**
   * A critical-risk task gets a review before code is written.
   *
   * <p>This is the one place {@code riskLevel} changes behaviour: on the tasks most able to cause
   * damage, thinking precedes typing.
   */
  static final class CriticalRiskReviewRule implements NextStepRule {

    @Override
    public Optional<NextStepRecommendation> evaluate(NextStepContext context) {
      Optional<Task> candidate =
          context.state().nextCandidateTasks().stream()
              .findFirst()
              .filter(task -> task.getRiskLevel() == RiskLevel.CRITICAL);
      if (candidate.isEmpty()) {
        return Optional.empty();
      }
      Task task = candidate.get();
      return Optional.of(
          new NextStepRecommendation(
              NextStepType.REVIEW_SECURITY,
              "Revisar riscos antes de iniciar: " + task.getTitle(),
              "Esta é a próxima tarefa elegível e está marcada como risco CRITICAL. "
                  + "A revisão vem antes da implementação.",
              task.getId(),
              NextStepPriority.HIGH,
              List.of(),
              List.of(
                  "Listar o que pode dar errado nesta tarefa",
                  "Definir critérios de aceite que cubram esses riscos",
                  "Só então iniciar a implementação"),
              PromptType.START_TASK));
    }

    @Override
    public String name() {
      return "critical-risk-review";
    }
  }

  /**
   * REGRAS D, E e F — start the next eligible task.
   *
   * <p>Candidates are already filtered to tasks whose dependencies are complete (REGRA E) and are
   * ordered by phase then position, so when a phase finishes the next one follows naturally
   * (REGRA F).
   */
  static final class StartNextReadyTaskRule implements NextStepRule {

    @Override
    public Optional<NextStepRecommendation> evaluate(NextStepContext context) {
      Optional<Task> candidate = context.state().nextCandidateTasks().stream().findFirst();
      if (candidate.isEmpty()) {
        return Optional.empty();
      }
      Task task = candidate.get();
      String phase =
          context.state().currentPhase() == null
              ? "sem fase definida"
              : context.state().currentPhase().getTitle();
      return Optional.of(
          new NextStepRecommendation(
              NextStepType.START_TASK,
              "Iniciar: " + task.getTitle(),
              "É a primeira tarefa elegível da fase "
                  + phase
                  + ": todas as suas dependências obrigatórias estão concluídas.",
              task.getId(),
              NextStepPriority.MEDIUM,
              List.of(),
              List.of(
                  "Implementar o objetivo da tarefa",
                  "Executar build e testes",
                  "Registrar a saída como evidência"),
              PromptType.START_TASK));
    }

    @Override
    public String name() {
      return "start-next-ready";
    }
  }

  /** REGRA E — there is work left, but nothing is eligible. The user has to unblock the graph. */
  static final class DependenciesPendingRule implements NextStepRule {

    @Override
    public Optional<NextStepRecommendation> evaluate(NextStepContext context) {
      List<String> waiting =
          context.state().allTasks().stream()
              .filter(task -> task.getStatus() == TaskStatus.PLANNED)
              .map(Task::getTitle)
              .toList();
      return Optional.of(
          new NextStepRecommendation(
              NextStepType.WAIT_FOR_USER,
              "Resolver dependências pendentes",
              "Existem tarefas em aberto, mas nenhuma está elegível: todas aguardam dependências "
                  + "que ainda não foram concluídas.",
              null,
              NextStepPriority.HIGH,
              waiting,
              List.of(
                  "Concluir uma dependência pendente",
                  "Ou revisar as dependências que não fazem mais sentido"),
              PromptType.ASK_FOR_EVIDENCE));
    }

    @Override
    public String name() {
      return "dependencies-pending";
    }
  }

  /**
   * Blocks progress if there are open critical security findings in the project.
   */
  static final class CriticalSecurityFindingRule implements NextStepRule {

    @Override
    public Optional<NextStepRecommendation> evaluate(NextStepContext context) {
      if (context.securityAssessment().isEmpty()) {
        return Optional.empty();
      }
      com.vibecode.guardian.domain.ProjectSecurityAssessment assessment =
          context.securityAssessment().get();
      if (assessment.critical() <= 0) {
        return Optional.empty();
      }

      Optional<Task> task = context.currentTask();
      return Optional.of(
          new NextStepRecommendation(
              NextStepType.REVIEW_SECURITY,
              "Revisar e resolver problemas críticos de segurança",
              "O projeto possui "
                  + assessment.critical()
                  + " problema(s) crítico(s) de segurança em aberto. O avanço para novas etapas ou conclusão do projeto está bloqueado até a resolução.",
              task.map(Task::getId).orElse(null),
              NextStepPriority.CRITICAL,
              assessment.blockingReasons(),
              List.of(
                  "Remover ou substituir credenciais e chaves privadas expostas",
                  "Rotacionar credenciais reais no provedor correspondente",
                  "Resolver formalmente os findings no painel de segurança"),
              PromptType.FIX_ERROR));
    }

    @Override
    public String name() {
      return "critical-security-review";
    }
  }
}
