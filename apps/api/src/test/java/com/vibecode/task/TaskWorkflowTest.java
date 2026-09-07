package com.vibecode.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.domain.EvidenceType;
import com.vibecode.project.application.ProjectService;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.PhaseStatus;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.shared.domain.DomainRuleException;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.CriterionStatus;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskAcceptanceCriterion;
import com.vibecode.task.domain.TaskStatus;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.vibecode.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TaskWorkflowTest {

  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TaskService tasks;
  @Autowired EvidenceService evidence;
  @Autowired TestIdentity identity;

  @BeforeEach
  void authenticate() {
    identity.createAndAuthenticate("Owner");
  }

  @AfterEach
  void signOut() {
    identity.clear();
  }


  private record Fixture(UUID projectId, RoadmapPhase phase) {}

  private Fixture fixture(String name) {
    UUID projectId = projects.create(name, "", "Ideia de " + name).getId();
    roadmaps.createOrGet(projectId);
    return new Fixture(projectId, roadmaps.addPhase(projectId, 1, "Fase única", null));
  }

  @Test
  @DisplayName("a task with unfinished dependencies stays PLANNED and cannot be started")
  void dependenciesGateReadiness() {
    Fixture fixture = fixture("Dependências");
    Task first = tasks.addTask(fixture.projectId(), fixture.phase().getId(), 1, "A", "Obj", RiskLevel.LOW);
    Task second =
        tasks.addTask(fixture.projectId(), fixture.phase().getId(), 2, "B", "Obj", RiskLevel.LOW);
    tasks.addDependency(fixture.projectId(), second.getId(), first.getId());

    assertThat(tasks.require(fixture.projectId(), second.getId()).getStatus())
        .isEqualTo(TaskStatus.PLANNED);
    assertThat(tasks.readyTasks(fixture.projectId())).extracting(Task::getTitle).containsExactly("A");

    assertThatThrownBy(() -> tasks.start(fixture.projectId(), second.getId()))
        .isInstanceOf(DomainRuleException.class)
        .hasMessageContaining("dependencies");
  }

  @Test
  @DisplayName("a dependency cycle is rejected rather than deadlocking the graph")
  void cyclesAreRejected() {
    Fixture fixture = fixture("Ciclos");
    Task a = tasks.addTask(fixture.projectId(), fixture.phase().getId(), 1, "A", "Obj", RiskLevel.LOW);
    Task b = tasks.addTask(fixture.projectId(), fixture.phase().getId(), 2, "B", "Obj", RiskLevel.LOW);
    Task c = tasks.addTask(fixture.projectId(), fixture.phase().getId(), 3, "C", "Obj", RiskLevel.LOW);

    tasks.addDependency(fixture.projectId(), b.getId(), a.getId());
    tasks.addDependency(fixture.projectId(), c.getId(), b.getId());

    assertThatThrownBy(() -> tasks.addDependency(fixture.projectId(), a.getId(), c.getId()))
        .isInstanceOf(DomainRuleException.class)
        .hasMessageContaining("cycle");

    assertThatThrownBy(() -> tasks.addDependency(fixture.projectId(), a.getId(), a.getId()))
        .isInstanceOf(DomainRuleException.class)
        .hasMessageContaining("itself");
  }

  @Test
  @DisplayName("a passing build does not satisfy an acceptance criterion nobody checked")
  void buildSuccessDoesNotSatisfyCriteria() {
    Fixture fixture = fixture("Critérios");
    Task task =
        tasks.addTask(fixture.projectId(), fixture.phase().getId(), 1, "Tarefa", "Obj", RiskLevel.LOW);
    TaskAcceptanceCriterion criterion =
        tasks.addCriterion(
            fixture.projectId(), task.getId(), "Uma pessoa revisou o modelo de ameaças", true);

    evidence.record(
        fixture.projectId(),
        task.getId(),
        EvidenceType.BUILD_RESULT,
        "BUILD SUCCESS\nTests run: 9, Failures: 0, Errors: 0",
        "maven");

    assertThat(tasks.criteriaOf(task.getId()))
        .singleElement()
        .extracting(TaskAcceptanceCriterion::getStatus)
        .isEqualTo(CriterionStatus.PENDING);
    assertThat(tasks.require(fixture.projectId(), task.getId()).getStatus())
        .isEqualTo(TaskStatus.NEEDS_VALIDATION);

    // Only an explicit decision moves it, and the decision is attributed.
    tasks.decideCriterion(
        fixture.projectId(), task.getId(), criterion.getId(), CriterionStatus.SATISFIED, "louvens");
    assertThat(tasks.criteriaOf(task.getId()).get(0).getDecidedBy()).isEqualTo("louvens");
  }

  @Test
  @DisplayName("a failing build leaves the task workable instead of marking it blocked")
  void failureDoesNotBlockTheTask() {
    Fixture fixture = fixture("Falha");
    Task task =
        tasks.addTask(fixture.projectId(), fixture.phase().getId(), 1, "Tarefa", "Obj", RiskLevel.LOW);

    evidence.record(
        fixture.projectId(), task.getId(), EvidenceType.BUILD_RESULT, "BUILD FAILURE", "maven");

    assertThat(tasks.require(fixture.projectId(), task.getId()).getStatus())
        .isEqualTo(TaskStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("an external obstacle does mark the task blocked")
  void blockingEvidenceBlocksTheTask() {
    Fixture fixture = fixture("Bloqueio");
    Task task =
        tasks.addTask(fixture.projectId(), fixture.phase().getId(), 1, "Tarefa", "Obj", RiskLevel.LOW);

    evidence.record(
        fixture.projectId(),
        task.getId(),
        EvidenceType.TERMINAL_OUTPUT,
        "docker: permission denied",
        "shell");

    assertThat(tasks.require(fixture.projectId(), task.getId()).getStatus())
        .isEqualTo(TaskStatus.BLOCKED);
  }

  @Test
  @DisplayName("recalculating readiness does not erase work that is underway")
  void recalculationPreservesInProgress() {
    Fixture fixture = fixture("Em andamento");
    Task task =
        tasks.addTask(fixture.projectId(), fixture.phase().getId(), 1, "Tarefa", "Obj", RiskLevel.LOW);
    tasks.start(fixture.projectId(), task.getId());

    // Adding another task triggers a full recalculation.
    tasks.addTask(fixture.projectId(), fixture.phase().getId(), 2, "Outra", "Obj", RiskLevel.LOW);

    assertThat(tasks.require(fixture.projectId(), task.getId()).getStatus())
        .isEqualTo(TaskStatus.IN_PROGRESS);
    assertThat(tasks.require(fixture.projectId(), task.getId()).getStartedAt()).isNotNull();
  }

  @Test
  @DisplayName("a task cannot be completed by passing the enum")
  void completionGoesThroughThePolicy() {
    Fixture fixture = fixture("Política");
    Task task =
        tasks.addTask(fixture.projectId(), fixture.phase().getId(), 1, "Tarefa", "Obj", RiskLevel.LOW);

    assertThatThrownBy(() -> tasks.require(fixture.projectId(), task.getId())
            .transitionTo(TaskStatus.COMPLETED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete()");
  }

  @Test
  @DisplayName("phase status follows its tasks")
  void phaseStatusIsDerived() {
    Fixture fixture = fixture("Fases");
    Task task =
        tasks.addTask(fixture.projectId(), fixture.phase().getId(), 1, "Tarefa", "Obj", RiskLevel.LOW);
    assertThat(roadmaps.requirePhase(fixture.projectId(), fixture.phase().getId()).getStatus())
        .isEqualTo(PhaseStatus.READY);

    tasks.start(fixture.projectId(), task.getId());
    assertThat(roadmaps.requirePhase(fixture.projectId(), fixture.phase().getId()).getStatus())
        .isEqualTo(PhaseStatus.IN_PROGRESS);

    evidence.record(
        fixture.projectId(), task.getId(), EvidenceType.BUILD_RESULT, "BUILD SUCCESS", "maven");
    assertThat(roadmaps.requirePhase(fixture.projectId(), fixture.phase().getId()).getStatus())
        .isEqualTo(PhaseStatus.COMPLETED);
  }
}
