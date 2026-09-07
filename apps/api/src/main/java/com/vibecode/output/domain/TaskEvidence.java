package com.vibecode.output.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Something that actually happened during a task: a build, a test run, a stack trace, an answer
 * from a model.
 *
 * <p>Evidence is append-only and has no setters. Old evidence is never edited or replaced, because
 * the value of an evidence trail is that it still shows the failure that came before the fix.
 */
@Entity
@Table(name = "task_evidence")
public class TaskEvidence {

  @Id private UUID id;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private EvidenceType type;

  @Column(name = "raw_content", nullable = false, columnDefinition = "TEXT")
  private String rawContent;

  /** Where the evidence came from: "maven", "claude", "user", a terminal name. */
  @Column(nullable = false, length = 80)
  private String source;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** For JPA only. */
  protected TaskEvidence() {}

  public TaskEvidence(
      UUID projectId, UUID taskId, EvidenceType type, String rawContent, String source) {
    this.id = UUID.randomUUID();
    this.projectId = projectId;
    this.taskId = taskId;
    this.type = type;
    this.rawContent = rawContent;
    this.source = source;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public EvidenceType getType() {
    return type;
  }

  public String getRawContent() {
    return rawContent;
  }

  public String getSource() {
    return source;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
