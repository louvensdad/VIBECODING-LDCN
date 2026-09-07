package com.vibecode.roadmap.web;

import com.vibecode.roadmap.domain.PhaseStatus;
import com.vibecode.roadmap.domain.Roadmap;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the roadmap endpoints.
 *
 * <p>JPA entities never cross the API boundary; these records are what the outside world sees.
 */
public final class RoadmapDtos {

  private RoadmapDtos() {}

  public record CreatePhaseRequest(
      @Min(1) int position,
      @NotBlank @Size(max = 160) String title,
      @Size(max = 5_000) String description) {}

  public record MovePhaseRequest(@Min(1) int position) {}

  public record PhaseResponse(
      UUID id,
      int position,
      String title,
      String description,
      PhaseStatus status,
      int totalTasks,
      int completedTasks,
      List<PhaseTaskResponse> tasks,
      Instant updatedAt) {

    public static PhaseResponse from(RoadmapPhase phase, List<Task> tasks) {
      return new PhaseResponse(
          phase.getId(),
          phase.getPosition(),
          phase.getTitle(),
          phase.getDescription(),
          phase.getStatus(),
          tasks.size(),
          (int) tasks.stream().filter(task -> task.getStatus().isFinished()).count(),
          tasks.stream().map(PhaseTaskResponse::from).toList(),
          phase.getUpdatedAt());
    }
  }

  /** Compact task view for the roadmap tree. */
  public record PhaseTaskResponse(UUID id, int position, String title, TaskStatus status) {

    public static PhaseTaskResponse from(Task task) {
      return new PhaseTaskResponse(
          task.getId(), task.getPosition(), task.getTitle(), task.getStatus());
    }
  }

  public record RoadmapResponse(
      UUID id,
      UUID projectId,
      int totalPhases,
      int completedPhases,
      List<PhaseResponse> phases,
      Instant createdAt) {

    public static RoadmapResponse from(Roadmap roadmap, List<PhaseResponse> phases) {
      return new RoadmapResponse(
          roadmap.getId(),
          roadmap.getProjectId(),
          phases.size(),
          (int) phases.stream().filter(phase -> phase.status() == PhaseStatus.COMPLETED).count(),
          phases,
          roadmap.getCreatedAt());
    }
  }
}
