package com.vibecode.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An append-only record of a security-relevant event. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "project_id")
  private UUID projectId;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 60)
  private AuditEventType eventType;

  @Column(name = "target_type", nullable = false, length = 60)
  private String targetType;

  @Column(name = "target_id", nullable = false, length = 120)
  private String targetId;

  @Column(name = "result", nullable = false, length = 40)
  private String result;

  @Column(name = "metadata", columnDefinition = "TEXT")
  private String metadata;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AuditEvent() {}

  public AuditEvent(
      UUID id,
      UUID projectId,
      UUID actorUserId,
      AuditEventType eventType,
      String targetType,
      String targetId,
      String result,
      String metadata) {
    this.id = Objects.requireNonNull(id, "id cannot be null");
    this.projectId = projectId;
    this.actorUserId = actorUserId;
    this.eventType = Objects.requireNonNull(eventType, "eventType cannot be null");
    this.targetType = targetType == null ? "UNSPECIFIED" : targetType;
    this.targetId = targetId == null ? "none" : targetId;
    this.result = result == null ? "SUCCESS" : result;
    this.metadata = metadata;
    this.createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getProjectId() { return projectId; }
  public UUID getActorUserId() { return actorUserId; }
  public AuditEventType getEventType() { return eventType; }
  public String getTargetType() { return targetType; }
  public String getTargetId() { return targetId; }
  public String getResult() { return result; }
  public String getMetadata() { return metadata; }
  public Instant getCreatedAt() { return createdAt; }
}

