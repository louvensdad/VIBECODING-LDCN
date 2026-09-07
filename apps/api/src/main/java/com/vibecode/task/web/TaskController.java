package com.vibecode.task.web;

import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskAcceptanceCriterion;
import com.vibecode.task.web.TaskDtos.CreateCriterionRequest;
import com.vibecode.task.web.TaskDtos.CreateDependencyRequest;
import com.vibecode.task.web.TaskDtos.CreateTaskRequest;
import com.vibecode.task.web.TaskDtos.CriterionResponse;
import com.vibecode.task.web.TaskDtos.DecideCriterionRequest;
import com.vibecode.task.web.TaskDtos.TaskResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class TaskController {

  private final TaskService tasks;

  public TaskController(TaskService tasks) {
    this.tasks = tasks;
  }

  @PostMapping("/roadmap/phases/{phaseId}/tasks")
  public ResponseEntity<TaskResponse> addTask(
      @PathVariable UUID projectId,
      @PathVariable UUID phaseId,
      @Valid @RequestBody CreateTaskRequest request) {
    Task task =
        tasks.addTask(
            projectId,
            phaseId,
            request.position(),
            request.title(),
            request.objective(),
            request.riskLevel());
    return ResponseEntity.status(HttpStatus.CREATED).body(detail(projectId, task.getId()));
  }

  @GetMapping("/tasks")
  public List<TaskResponse> list(@PathVariable UUID projectId) {
    return tasks.listOrdered(projectId).stream().map(TaskResponse::summary).toList();
  }

  @GetMapping("/tasks/{taskId}")
  public TaskResponse get(@PathVariable UUID projectId, @PathVariable UUID taskId) {
    return detail(projectId, taskId);
  }

  @PostMapping("/tasks/{taskId}/dependencies")
  public ResponseEntity<TaskResponse> addDependency(
      @PathVariable UUID projectId,
      @PathVariable UUID taskId,
      @Valid @RequestBody CreateDependencyRequest request) {
    tasks.addDependency(projectId, taskId, request.dependsOnTaskId());
    return ResponseEntity.status(HttpStatus.CREATED).body(detail(projectId, taskId));
  }

  @PostMapping("/tasks/{taskId}/criteria")
  public ResponseEntity<CriterionResponse> addCriterion(
      @PathVariable UUID projectId,
      @PathVariable UUID taskId,
      @Valid @RequestBody CreateCriterionRequest request) {
    TaskAcceptanceCriterion criterion =
        tasks.addCriterion(projectId, taskId, request.description(), request.required());
    return ResponseEntity.status(HttpStatus.CREATED).body(CriterionResponse.from(criterion));
  }

  /**
   * Records an explicit decision on a criterion. This is the only way a criterion becomes
   * satisfied; a passing build never satisfies one on its own.
   */
  @PatchMapping("/tasks/{taskId}/criteria/{criterionId}")
  public CriterionResponse decideCriterion(
      @PathVariable UUID projectId,
      @PathVariable UUID taskId,
      @PathVariable UUID criterionId,
      @Valid @RequestBody DecideCriterionRequest request) {
    return CriterionResponse.from(
        tasks.decideCriterion(
            projectId, taskId, criterionId, request.status(), request.decidedBy()));
  }

  @PostMapping("/tasks/{taskId}/start")
  public TaskResponse start(@PathVariable UUID projectId, @PathVariable UUID taskId) {
    tasks.start(projectId, taskId);
    return detail(projectId, taskId);
  }

  private TaskResponse detail(UUID projectId, UUID taskId) {
    Task task = tasks.require(projectId, taskId);
    return TaskResponse.from(
        task, tasks.mandatoryDependencies(taskId), tasks.criteriaOf(taskId));
  }
}
