package com.vibecode.audit.application;

import com.vibecode.audit.domain.AuditEvent;
import com.vibecode.audit.domain.AuditEventType;
import com.vibecode.audit.infrastructure.AuditEventRepository;
import com.vibecode.identity.domain.CurrentUser;
import com.vibecode.identity.domain.CurrentUserProvider;
import com.vibecode.project.application.ProjectService;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the append-only security audit trail.
 *
 * <p>Never allows modification or deletion of recorded audit events.
 */
@Service
@Transactional
public class AuditService {

  private final AuditEventRepository events;
  private final CurrentUserProvider currentUserProvider;
  private final ProjectService projects;

  public AuditService(
      AuditEventRepository events,
      CurrentUserProvider currentUserProvider,
      @Lazy ProjectService projects) {
    this.events = events;
    this.currentUserProvider = currentUserProvider;
    this.projects = projects;
  }

  /**
   * Records an event in its own transaction, like {@link #recordWithActor}.
   *
   * <p>Callers span both kinds of transaction: the guardian writes, while prompt generation is
   * read-only. An audit write inside a read-only transaction is silently discarded, which is how
   * PROMPT_BLOCKED went missing. An event must not depend on the transaction semantics of whoever
   * happened to trigger it.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AuditEvent record(
      UUID projectId,
      AuditEventType eventType,
      String targetType,
      String targetId,
      String result,
      String metadata) {
    UUID actorUserId =
        currentUserProvider.current().map(CurrentUser::id).orElse(null);

    AuditEvent event =
        new AuditEvent(
            UUID.randomUUID(),
            projectId,
            actorUserId,
            eventType,
            targetType,
            targetId,
            result,
            metadata);
    return events.save(event);
  }

  /**
   * Records an event in its own transaction.
   *
   * <p>The events that matter most — a denied cross-user access, a failed login — are written on a
   * path that then throws. In the caller's transaction that write would be rolled back by the very
   * exception it exists to record, so the trail would silently lose exactly the events an attack
   * produces. A separate transaction commits the fact regardless of what happens to the request.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AuditEvent recordWithActor(
      UUID projectId,
      UUID actorUserId,
      AuditEventType eventType,
      String targetType,
      String targetId,
      String result,
      String metadata) {
    AuditEvent event =
        new AuditEvent(
            UUID.randomUUID(),
            projectId,
            actorUserId,
            eventType,
            targetType,
            targetId,
            result,
            metadata);
    return events.save(event);
  }

  @Transactional(readOnly = true)
  public List<AuditEvent> listForProject(UUID projectId) {
    projects.requireReadable(projectId);
    return events.findByProjectIdOrderByCreatedAtDesc(projectId);
  }
}
