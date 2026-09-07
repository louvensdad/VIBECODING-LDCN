package com.vibecode.audit.web;

import com.vibecode.audit.domain.AuditEvent;
import com.vibecode.audit.domain.AuditEventType;
import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
    UUID id,
    UUID projectId,
    UUID actorUserId,
    AuditEventType eventType,
    String targetType,
    String targetId,
    String result,
    String metadata,
    Instant createdAt) {

  public static AuditEventResponse from(AuditEvent event) {
    return new AuditEventResponse(
        event.getId(),
        event.getProjectId(),
        event.getActorUserId(),
        event.getEventType(),
        event.getTargetType(),
        event.getTargetId(),
        event.getResult(),
        event.getMetadata(),
        event.getCreatedAt());
  }
}

