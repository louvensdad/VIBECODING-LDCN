package com.vibecode.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.brain.application.MemoryProposalService;
import com.vibecode.brain.domain.MemoryProposalTrigger;
import com.vibecode.brain.domain.MemoryUpdateProposal;
import com.vibecode.guide.application.DeterministicNextStepEngine;
import com.vibecode.guide.application.DeterministicProjectGuide;
import com.vibecode.guide.domain.GuidanceReport;
import com.vibecode.guide.domain.NextStepRecommendation;
import com.vibecode.guide.domain.NextStepType;
import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.domain.EvidenceType;
import com.vibecode.output.domain.OutputAnalysisStatus;
import com.vibecode.project.application.ProjectService;
import com.vibecode.project.domain.Project;
import com.vibecode.prompt.application.DeterministicPromptBuilder;
import com.vibecode.prompt.domain.GeneratedPrompt;
import com.vibecode.prompt.domain.PromptRequest;
import com.vibecode.prompt.domain.PromptType;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.PhaseStatus;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.state.application.ProjectStateService;
import com.vibecode.state.domain.ProjectState;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.CriterionStatus;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskAcceptanceCriterion;
import com.vibecode.task.domain.TaskStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The acceptance case for this phase: a project walked from an idea to a correction prompt with no
 * LLM anywhere in the loop.
 *
 * <pre>
 *   project -&gt; roadmap -&gt; task -&gt; output -&gt; evidence -&gt; analysis -&gt; next step -&gt; prompt
 * </pre>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BarberFlowAcceptanceTest {

  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TaskService tasks;
  @Autowired EvidenceService evidence;
  @Autowired ProjectStateService state;
  @Autowired DeterministicNextStepEngine nextStep;
  @Autowired DeterministicProjectGuide guide;
  @Autowired DeterministicPromptBuilder prompts;
  @Autowired MemoryProposalService memory;

  @Test
  @Order(1)
  @DisplayName("BarberFlow: from roadmap to correction prompt, with no model involved")
  void barberFlow() {
    // 1. The project.
    Project project =
        projects.create("BarberFlow", "Booking for barbers", "Agendamento online para barbearias");
    UUID id = project.getId();

    // 2. The roadmap: Setup, Authentication, Dashboard.
    roadmaps.createOrGet(id);
    RoadmapPhase setup = roadmaps.addPhase(id, 1, "Setup", "Base do projeto");
    RoadmapPhase auth = roadmaps.addPhase(id, 2, "Authentication", "Login e sessão");
    roadmaps.addPhase(id, 3, "Dashboard", "Painel do barbeiro");

    // 3. Two tasks in Authentication, B depending on A.
    Task createUser =
        tasks.addTask(id, auth.getId(), 1, "Create User", "Criar a entidade User", RiskLevel.MEDIUM);
    Task springSecurity =
        tasks.addTask(
            id, auth.getId(), 2, "Configure Spring Security", "Configurar o Spring Security",
            RiskLevel.HIGH);
    tasks.addDependency(id, springSecurity.getId(), createUser.getId());

    TaskAcceptanceCriterion userCriterion =
        tasks.addCriterion(id, createUser.getId(), "A entidade User compila e tem testes", true);
    TaskAcceptanceCriterion securityCriterion =
        tasks.addCriterion(
            id, springSecurity.getId(), "Endpoints protegidos exigem autenticação", true);

    // B cannot start while A is open.
    assertThat(tasks.require(id, springSecurity.getId()).getStatus()).isEqualTo(TaskStatus.PLANNED);
    assertThat(tasks.require(id, createUser.getId()).getStatus()).isEqualTo(TaskStatus.READY);

    // 4. A is completed with real evidence plus an explicit criterion decision.
    evidence.record(
        id,
        createUser.getId(),
        EvidenceType.BUILD_RESULT,
        "BUILD SUCCESS\nTests run: 12, Failures: 0, Errors: 0",
        "maven");

    // Evidence alone does not close the task: the criterion is still pending.
    assertThat(tasks.require(id, createUser.getId()).getStatus())
        .isEqualTo(TaskStatus.NEEDS_VALIDATION);

    tasks.decideCriterion(
        id, createUser.getId(), userCriterion.getId(), CriterionStatus.SATISFIED, "louvens");
    evidence.record(
        id,
        createUser.getId(),
        EvidenceType.TEST_RESULT,
        "BUILD SUCCESS\nTests run: 12, Failures: 0, Errors: 0",
        "maven");
    assertThat(tasks.require(id, createUser.getId()).getStatus()).isEqualTo(TaskStatus.COMPLETED);

    // 5. With A done, the next step is B.
    NextStepRecommendation afterA = nextStep.recommend(id);
    assertThat(afterA.type()).isEqualTo(NextStepType.START_TASK);
    assertThat(afterA.taskId()).isEqualTo(springSecurity.getId());
    assertThat(afterA.title()).contains("Configure Spring Security");

    // 6. B fails to build.
    evidence.record(
        id,
        springSecurity.getId(),
        EvidenceType.BUILD_RESULT,
        "BUILD FAILURE\nCompilation error in SecurityConfig.java:42",
        "maven");

    // 7. The next step becomes a correction, not a new feature.
    NextStepRecommendation afterFailure = nextStep.recommend(id);
    assertThat(afterFailure.type()).isEqualTo(NextStepType.FIX_ERROR);
    assertThat(afterFailure.taskId()).isEqualTo(springSecurity.getId());
    assertThat(afterFailure.priority().name()).isEqualTo("CRITICAL");

    // 8. The correction prompt carries the real error.
    GeneratedPrompt fixPrompt =
        prompts.build(new PromptRequest(id, springSecurity.getId(), PromptType.FIX_ERROR));
    assertThat(fixPrompt.content())
        .contains("Compilation error in SecurityConfig.java:42")
        .contains("NÃO CONTINUE PARA NOVAS FUNCIONALIDADES")
        .contains("COMPILATION_ERROR");
    assertThat(fixPrompt.contextSources()).contains("Latest evidence");

    // 9. The fix lands.
    evidence.record(
        id,
        springSecurity.getId(),
        EvidenceType.BUILD_RESULT,
        "BUILD SUCCESS\nTests run: 18, Failures: 0, Errors: 0",
        "maven");
    assertThat(nextStep.recommend(id).type()).isNotEqualTo(NextStepType.FIX_ERROR);

    // 10. B closes once its criterion is decided too.
    assertThat(tasks.require(id, springSecurity.getId()).getStatus())
        .isEqualTo(TaskStatus.NEEDS_VALIDATION);
    tasks.decideCriterion(
        id, springSecurity.getId(), securityCriterion.getId(), CriterionStatus.SATISFIED, "louvens");
    evidence.record(
        id,
        springSecurity.getId(),
        EvidenceType.TEST_RESULT,
        "BUILD SUCCESS\nTests run: 18, Failures: 0, Errors: 0",
        "maven");
    assertThat(tasks.require(id, springSecurity.getId()).getStatus()).isEqualTo(TaskStatus.COMPLETED);

    // The Authentication phase is now done, and the plan moves on by itself.
    assertThat(roadmaps.requirePhase(id, auth.getId()).getStatus()).isEqualTo(PhaseStatus.COMPLETED);
    assertThat(roadmaps.requirePhase(id, setup.getId()).getStatus()).isEqualTo(PhaseStatus.PLANNED);

    ProjectState finalState = state.of(id);
    assertThat(finalState.completedTasks()).isEqualTo(2);
    assertThat(finalState.totalTasks()).isEqualTo(2);
    assertThat(finalState.progressPercentage()).isEqualTo(100);

    // The guide can narrate all of it without a model.
    GuidanceReport report = guide.describe(id);
    assertThat(report.whatWasCompleted()).containsExactly("Create User", "Configure Spring Security");
    assertThat(report.reason()).isNotBlank();

    // The events left proposed memory behind, and none of it was written automatically.
    List<MemoryUpdateProposal> pending = memory.listPending(id);
    assertThat(pending).isNotEmpty();
    assertThat(pending)
        .extracting(MemoryUpdateProposal::getTrigger)
        .contains(
            MemoryProposalTrigger.ERROR_FOUND,
            MemoryProposalTrigger.ERROR_RESOLVED,
            MemoryProposalTrigger.TASK_COMPLETED);
  }

  @Test
  @Order(2)
  @DisplayName("evidence stays append-only: the failure is still there after the fix")
  void evidenceHistoryIsPreserved() {
    Project project = projects.create("Trilha", "", "Histórico de evidência");
    UUID id = project.getId();
    roadmaps.createOrGet(id);
    RoadmapPhase phase = roadmaps.addPhase(id, 1, "Fase", "");
    Task task = tasks.addTask(id, phase.getId(), 1, "Tarefa", "Objetivo", RiskLevel.LOW);

    evidence.record(id, task.getId(), EvidenceType.BUILD_RESULT, "BUILD FAILURE", "maven");
    evidence.record(id, task.getId(), EvidenceType.BUILD_RESULT, "BUILD SUCCESS", "maven");

    var history = evidence.listForTask(id, task.getId());
    assertThat(history).hasSize(2);
    assertThat(history.get(0).getRawContent()).isEqualTo("BUILD SUCCESS");
    assertThat(history.get(1).getRawContent()).isEqualTo("BUILD FAILURE");

    assertThat(evidence.latestAnalysisForTask(task.getId()))
        .get()
        .extracting(record -> record.getStatus())
        .isEqualTo(OutputAnalysisStatus.SUCCESS);
  }
}
