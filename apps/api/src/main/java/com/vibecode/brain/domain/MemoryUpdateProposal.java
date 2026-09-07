package com.vibecode.brain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A candidate memory change, raised by something that happened rather than written into memory
 * directly.
 *
 * <p>A proposal is not memory. Nothing here reaches the Project Brain until a person accepts it,
 * which is what keeps the platform — and later an LLM — from silently rewriting the project's
 * truth:
 *
 * <pre>
 *   event or LLM result -&gt; MemoryUpdateProposal -&gt; review -&gt; BrainEntry
 * </pre>
 */
@Entity
@Table(name = "memory_update_proposals")
public class MemoryUpdateProposal {

  @Id private UUID id;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  /** What made this proposal appear. */
  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_event", nullable = false, length = 40)
  private MemoryProposalTrigger trigger;

  @Enumerated(EnumType.STRING)
  @Column(name = "proposed_type", nullable = false, length = 40)
  private BrainEntryType proposedType;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(nullable = false, length = 80)
  private String source;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private MemoryProposalStatus status;

  /** The entry created when the proposal was accepted. Null while pending or rejected. */
  @Column(name = "resulting_entry_id")
  private UUID resultingEntryId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  /** For JPA only. */
  protected MemoryUpdateProposal() {}

  public MemoryUpdateProposal(
      UUID projectId,
      MemoryProposalTrigger trigger,
      BrainEntryType proposedType,
      String title,
      String content,
      String source) {
    this.id = UUID.randomUUID();
    this.projectId = projectId;
    this.trigger = trigger;
    this.proposedType = proposedType;
    this.title = title;
    this.content = content;
    this.source = source;
    this.status = MemoryProposalStatus.PENDING;
    this.createdAt = Instant.now();
  }

  /**
   * The entry this proposal becomes. Callable only after acceptance — the guard is what makes the
   * review step real rather than advisory.
   */
  public BrainEntry toEntry() {
    if (status != MemoryProposalStatus.ACCEPTED) {
      throw new IllegalStateException("Only an accepted proposal can enter the Project Brain");
    }
    return new BrainEntry(projectId, proposedType, title, content, source);
  }

  public void accept() {
    requirePending();
    this.status = MemoryProposalStatus.ACCEPTED;
    this.reviewedAt = Instant.now();
  }

  public void reject() {
    requirePending();
    this.status = MemoryProposalStatus.REJECTED;
    this.reviewedAt = Instant.now();
  }

  public void recordResultingEntry(UUID entryId) {
    this.resultingEntryId = entryId;
  }

  private void requirePending() {
    if (status != MemoryProposalStatus.PENDING) {
      throw new IllegalStateException("This proposal was already reviewed");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public MemoryProposalTrigger getTrigger() {
    return trigger;
  }

  public BrainEntryType getProposedType() {
    return proposedType;
  }

  public String getTitle() {
    return title;
  }

  public String getContent() {
    return content;
  }

  public String getSource() {
    return source;
  }

  public MemoryProposalStatus getStatus() {
    return status;
  }

  public UUID getResultingEntryId() {
    return resultingEntryId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getReviewedAt() {
    return reviewedAt;
  }
}
