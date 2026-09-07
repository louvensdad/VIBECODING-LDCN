package com.vibecode.task.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One step of the tracked project.
 *
 * <p>A task never sets itself to {@link TaskStatus#COMPLETED}: completion goes through {@code
 * TaskCompletionPolicy}, which requires satisfied dependencies, satisfied criteria and supporting
 * evidence. The entity enforces the last line of that rule — {@link #complete()} refuses to run
 * without it.
 */
@Entity
@Table(name = "tasks")
public class Task {

  @Id private UUID id;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "phase_id", nullable = false)
  private UUID phaseId;

  /** 1-based order within the phase. Unique per phase, enforced by the schema. */
  @Column(nullable = false)
  private int position;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String objective;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private TaskStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk_level", nullable = false, length = 20)
  private RiskLevel riskLevel;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  /** For JPA only. */
  protected Task() {}

  public Task(
      UUID projectId, UUID phaseId, int position, String title, String objective, RiskLevel risk) {
    this.id = UUID.randomUUID();
    this.projectId = projectId;
    this.phaseId = phaseId;
    this.position = position;
    this.title = title;
    this.objective = objective;
    this.riskLevel = risk;
    this.status = TaskStatus.PLANNED;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  /**
   * Moves the task to a status the user or the evidence flow drives directly.
   *
   * <p>{@link TaskStatus#COMPLETED} is rejected here on purpose: it has its own entry point so no
   * caller can complete a task by passing an enum value.
   */
  public void transitionTo(TaskStatus newStatus) {
    if (newStatus == TaskStatus.COMPLETED) {
      throw new IllegalArgumentException("Use complete(), which requires the completion policy");
    }
    apply(newStatus);
  }

  /**
   * Marks the task finished. Only {@code TaskCompletionPolicy} should reach this, after checking
   * dependencies, criteria and evidence.
   */
  public void complete() {
    apply(TaskStatus.COMPLETED);
    this.completedAt = this.updatedAt;
  }

  /**
   * Recomputed readiness, applied by the status recalculator.
   *
   * <p>Statuses the user is holding — in progress, blocked, awaiting validation — are left alone,
   * and a finished task is never reopened. Without this guard a recalculation would silently erase
   * the fact that someone is mid-task.
   */
  public void applyReadiness(boolean dependenciesSatisfied) {
    if (status.isFinished() || status.isUserHeld()) {
      return;
    }
    apply(dependenciesSatisfied ? TaskStatus.READY : TaskStatus.PLANNED);
  }

  public void moveTo(int newPosition) {
    this.position = newPosition;
    this.updatedAt = Instant.now();
  }

  private void apply(TaskStatus newStatus) {
    if (this.status == newStatus) {
      return;
    }
    this.status = newStatus;
    this.updatedAt = Instant.now();
    if (newStatus == TaskStatus.IN_PROGRESS && startedAt == null) {
      this.startedAt = this.updatedAt;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public UUID getPhaseId() {
    return phaseId;
  }

  public int getPosition() {
    return position;
  }

  public String getTitle() {
    return title;
  }

  public String getObjective() {
    return objective;
  }

  public TaskStatus getStatus() {
    return status;
  }

  public RiskLevel getRiskLevel() {
    return riskLevel;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
