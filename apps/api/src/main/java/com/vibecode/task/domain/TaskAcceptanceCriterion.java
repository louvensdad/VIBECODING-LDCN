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
 * One verifiable condition that closes a task.
 *
 * <p>Criteria exist so "done" has a definition written before the work starts, instead of being
 * decided afterwards by whoever is looking at the output.
 */
@Entity
@Table(name = "task_acceptance_criteria")
public class TaskAcceptanceCriterion {

  @Id private UUID id;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String description;

  /** A task cannot complete while a required criterion is unsatisfied. */
  @Column(nullable = false)
  private boolean required;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private CriterionStatus status;

  /** Who or what decided the criterion was met. Null while pending. */
  @Column(name = "decided_by", length = 80)
  private String decidedBy;

  @Column(name = "decided_at")
  private Instant decidedAt;

  /** For JPA only. */
  protected TaskAcceptanceCriterion() {}

  public TaskAcceptanceCriterion(UUID taskId, String description, boolean required) {
    this.id = UUID.randomUUID();
    this.taskId = taskId;
    this.description = description;
    this.required = required;
    this.status = CriterionStatus.PENDING;
  }

  /**
   * Records an explicit decision about this criterion. {@code decidedBy} is mandatory so a
   * satisfied criterion always names who vouched for it.
   */
  public void decide(CriterionStatus newStatus, String decidedBy) {
    if (decidedBy == null || decidedBy.isBlank()) {
      throw new IllegalArgumentException("A criterion decision must name who made it");
    }
    this.status = newStatus;
    this.decidedBy = decidedBy;
    this.decidedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public String getDescription() {
    return description;
  }

  public boolean isRequired() {
    return required;
  }

  public CriterionStatus getStatus() {
    return status;
  }

  public String getDecidedBy() {
    return decidedBy;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }
}
