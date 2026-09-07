package com.vibecode.task.web;

import com.vibecode.task.domain.CriterionStatus;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskAcceptanceCriterion;
import com.vibecode.task.domain.TaskStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the task endpoints. */
public final class TaskDtos {

  private TaskDtos() {}

  public record CreateTaskRequest(
      @Min(1) int position,
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 10_000) String objective,
      @NotNull RiskLevel riskLevel) {}

  public record CreateDependencyRequest(@NotNull UUID dependsOnTaskId) {}

  public record CreateCriterionRequest(
      @NotBlank @Size(max = 5_000) String description, boolean required) {}

  /**
   * A criterion decision must say who made it — a satisfied criterion with no name behind it is
   * indistinguishable from one nobody checked.
   */
  public record DecideCriterionRequest(
      @NotNull CriterionStatus status, @NotBlank @Size(max = 80) String decidedBy) {}

  public record CriterionResponse(
      UUID id,
      String description,
      boolean required,
      CriterionStatus status,
      String decidedBy,
      Instant decidedAt) {

    public static CriterionResponse from(TaskAcceptanceCriterion criterion) {
      return new CriterionResponse(
          criterion.getId(),
          criterion.getDescription(),
          criterion.isRequired(),
          criterion.getStatus(),
          criterion.getDecidedBy(),
          criterion.getDecidedAt());
    }
  }

  public record TaskResponse(
      UUID id,
      UUID projectId,
      UUID phaseId,
      int position,
      String title,
      String objective,
      TaskStatus status,
      RiskLevel riskLevel,
      List<UUID> dependsOn,
      List<CriterionResponse> acceptanceCriteria,
      Instant createdAt,
      Instant updatedAt,
      Instant startedAt,
      Instant completedAt) {

    public static TaskResponse from(
        Task task, List<Task> dependencies, List<TaskAcceptanceCriterion> criteria) {
      return new TaskResponse(
          task.getId(),
          task.getProjectId(),
          task.getPhaseId(),
          task.getPosition(),
          task.getTitle(),
          task.getObjective(),
          task.getStatus(),
          task.getRiskLevel(),
          dependencies.stream().map(Task::getId).toList(),
          criteria.stream().map(CriterionResponse::from).toList(),
          task.getCreatedAt(),
          task.getUpdatedAt(),
          task.getStartedAt(),
          task.getCompletedAt());
    }

    public static TaskResponse summary(Task task) {
      return from(task, List.of(), List.of());
    }
  }
}
