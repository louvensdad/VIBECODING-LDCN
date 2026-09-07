package com.vibecode.task.application;

import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.project.application.ProjectService;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.shared.domain.DomainRuleException;
import com.vibecode.shared.domain.ResourceNotFoundException;
import com.vibecode.task.application.TaskCompletionPolicy.CompletionAssessment;
import com.vibecode.task.domain.CriterionStatus;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskAcceptanceCriterion;
import com.vibecode.task.domain.TaskDependency;
import com.vibecode.task.domain.TaskStatus;
import com.vibecode.task.infrastructure.TaskAcceptanceCriterionRepository;
import com.vibecode.task.infrastructure.TaskDependencyRepository;
import com.vibecode.task.infrastructure.TaskRepository;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns tasks, their dependencies and their acceptance criteria. */
@Service
@Transactional
public class TaskService {

  private final ProjectService projects;
  private final RoadmapService roadmaps;
  private final TaskRepository tasks;
  private final TaskDependencyRepository dependencies;
  private final TaskAcceptanceCriterionRepository criteria;
  private final TaskStatusRecalculator recalculator;
  private final TaskCompletionPolicy completionPolicy;

  public TaskService(
      ProjectService projects,
      RoadmapService roadmaps,
      TaskRepository tasks,
      TaskDependencyRepository dependencies,
      TaskAcceptanceCriterionRepository criteria,
      TaskStatusRecalculator recalculator,
      TaskCompletionPolicy completionPolicy) {
    this.projects = projects;
    this.roadmaps = roadmaps;
    this.tasks = tasks;
    this.dependencies = dependencies;
    this.criteria = criteria;
    this.recalculator = recalculator;
    this.completionPolicy = completionPolicy;
  }

  public Task addTask(
      UUID projectId,
      UUID phaseId,
      int position,
      String title,
      String objective,
      RiskLevel riskLevel) {
    projects.requireWritable(projectId);
    roadmaps.requirePhase(projectId, phaseId);
    if (position < 1) {
      throw new DomainRuleException("Task position starts at 1");
    }
    if (tasks.existsByPhaseIdAndPosition(phaseId, position)) {
      throw new DomainRuleException("Task position " + position + " is already taken in this phase");
    }
    Task task = tasks.save(new Task(projectId, phaseId, position, title, objective, riskLevel));
    recalculator.recalculate(projectId);
    return task;
  }

  /**
   * Records that one task must wait for another.
   *
   * <p>Cycles are rejected: a dependency graph with a loop would leave every task in it permanently
   * unable to become READY, with nothing explaining why.
   */
  public void addDependency(UUID projectId, UUID taskId, UUID dependsOnTaskId) {
    projects.requireWritable(projectId);
    Task task = require(projectId, taskId);
    Task dependency = require(projectId, dependsOnTaskId);
    if (task.getId().equals(dependency.getId())) {
      throw new DomainRuleException("A task cannot depend on itself");
    }
    if (dependencies.existsByTaskIdAndDependencyTaskId(taskId, dependsOnTaskId)) {
      throw new DomainRuleException("That dependency already exists");
    }
    if (wouldCreateCycle(projectId, taskId, dependsOnTaskId)) {
      throw new DomainRuleException(
          "That dependency would create a cycle: "
              + dependency.getTitle()
              + " already depends on "
              + task.getTitle());
    }
    dependencies.save(new TaskDependency(taskId, dependsOnTaskId));
    recalculator.recalculate(projectId);
  }

  public TaskAcceptanceCriterion addCriterion(
      UUID projectId, UUID taskId, String description, boolean required) {
    projects.requireWritable(projectId);
    require(projectId, taskId);
    return criteria.save(new TaskAcceptanceCriterion(taskId, description, required));
  }

  /**
   * Records an explicit decision about a criterion.
   *
   * <p>This is the only way a criterion becomes satisfied. It is never inferred from a passing
   * build, because a green build proves the code compiles, not that the condition someone wrote
   * down was checked.
   */
  public TaskAcceptanceCriterion decideCriterion(
      UUID projectId, UUID taskId, UUID criterionId, CriterionStatus status, String decidedBy) {
    projects.requireWritable(projectId);
    require(projectId, taskId);
    TaskAcceptanceCriterion criterion =
        criteria
            .findById(criterionId)
            .orElseThrow(() -> new ResourceNotFoundException("Criterion not found: " + criterionId));
    if (!criterion.getTaskId().equals(taskId)) {
      throw new DomainRuleException("This criterion belongs to another task");
    }
    criterion.decide(status, decidedBy);
    return criterion;
  }

  public Task start(UUID projectId, UUID taskId) {
    projects.requireWritable(projectId);
    Task task = require(projectId, taskId);
    if (task.getStatus() == TaskStatus.PLANNED) {
      throw new DomainRuleException(
          "This task still has unfinished dependencies and cannot be started");
    }
    task.transitionTo(TaskStatus.IN_PROGRESS);
    recalculator.recalculate(projectId);
    return task;
  }

  /**
   * Applies the completion policy. The task closes only if every condition holds; otherwise it is
   * moved to NEEDS_VALIDATION and the caller is told what is missing.
   */
  public CompletionAssessment tryComplete(
      UUID projectId, UUID taskId, Optional<OutputAnalysisRecord> latest) {
    projects.requireWritable(projectId);
    Task task = require(projectId, taskId);
    CompletionAssessment assessment =
        completionPolicy.evaluate(mandatoryDependencies(taskId), criteriaOf(taskId), latest);
    if (assessment.canComplete()) {
      task.complete();
    } else if (!task.getStatus().isFinished()) {
      task.transitionTo(TaskStatus.NEEDS_VALIDATION);
    }
    recalculator.recalculate(projectId);
    return assessment;
  }

  public void markBlocked(UUID projectId, UUID taskId) {
    projects.requireWritable(projectId);
    require(projectId, taskId).transitionTo(TaskStatus.BLOCKED);
    recalculator.recalculate(projectId);
  }

  /**
   * A failing build leaves the task in progress, not blocked. The work is still the user's to do;
   * calling it blocked would hide it from the recalculator and strand it.
   */
  public void markNeedsWork(UUID projectId, UUID taskId) {
    projects.requireWritable(projectId);
    Task task = require(projectId, taskId);
    if (!task.getStatus().isFinished()) {
      task.transitionTo(TaskStatus.IN_PROGRESS);
    }
    recalculator.recalculate(projectId);
  }

  @Transactional(readOnly = true)
  public Task require(UUID projectId, UUID taskId) {
    // Authorize the project before touching the task. Matching task.projectId against the path is
    // not enough on its own: it only proves the pair is consistent, not that the caller owns it.
    projects.requireReadable(projectId);
    Task task =
        tasks
            .findById(taskId)
            .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
    if (!task.getProjectId().equals(projectId)) {
      throw new DomainRuleException("This task belongs to another project");
    }
    return task;
  }

  @Transactional(readOnly = true)
  public List<Task> listOrdered(UUID projectId) {
    projects.requireReadable(projectId);
    Map<UUID, Integer> phaseOrder = phaseOrder(projectId);
    return tasks.findByProjectId(projectId).stream()
        .sorted(
            Comparator.comparingInt(
                    (Task task) -> phaseOrder.getOrDefault(task.getPhaseId(), Integer.MAX_VALUE))
                .thenComparingInt(Task::getPosition))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<Task> listByPhase(UUID phaseId) {
    return tasks.findByPhaseIdOrderByPosition(phaseId);
  }

  @Transactional(readOnly = true)
  public List<TaskAcceptanceCriterion> criteriaOf(UUID taskId) {
    return criteria.findByTaskId(taskId);
  }

  @Transactional(readOnly = true)
  public List<Task> mandatoryDependencies(UUID taskId) {
    return dependencies.findByTaskId(taskId).stream()
        .map(dependency -> tasks.findById(dependency.getDependencyTaskId()).orElse(null))
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  private Map<UUID, Integer> phaseOrder(UUID projectId) {
    List<RoadmapPhase> phases = roadmaps.listPhases(projectId);
    return phases.stream()
        .collect(Collectors.toMap(RoadmapPhase::getId, RoadmapPhase::getPosition));
  }

  /** Walks the existing edges from the prospective dependency looking for a way back to the task. */
  private boolean wouldCreateCycle(UUID projectId, UUID taskId, UUID dependsOnTaskId) {
    Map<UUID, List<UUID>> edges =
        tasks.findByProjectId(projectId).stream()
            .collect(
                Collectors.toMap(
                    Task::getId,
                    task ->
                        dependencies.findByTaskId(task.getId()).stream()
                            .map(TaskDependency::getDependencyTaskId)
                            .collect(Collectors.toList())));

    Deque<UUID> queue = new ArrayDeque<>(List.of(dependsOnTaskId));
    Set<UUID> seen = new HashSet<>();
    while (!queue.isEmpty()) {
      UUID current = queue.poll();
      if (current.equals(taskId)) {
        return true;
      }
      if (!seen.add(current)) {
        continue;
      }
      queue.addAll(edges.getOrDefault(current, List.of()));
    }
    return false;
  }

  /** Tasks that could be started right now, in plan order. */
  @Transactional(readOnly = true)
  public List<Task> readyTasks(UUID projectId) {
    return listOrdered(projectId).stream()
        .filter(task -> task.getStatus() == TaskStatus.READY)
        .toList();
  }

}
