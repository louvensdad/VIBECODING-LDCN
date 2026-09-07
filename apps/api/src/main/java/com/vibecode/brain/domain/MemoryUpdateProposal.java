package com.vibecode.brain.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A candidate memory change produced outside the platform — typically from an LLM answer.
 *
 * <p>A proposal is not memory. Nothing here reaches the Project Brain until it is validated and
 * accepted, which is what keeps an external model from silently rewriting the project's truth:
 *
 * <pre>
 *   LLM result -&gt; MemoryUpdateProposal -&gt; validation -&gt; BrainEntry
 * </pre>
 *
 * <p>Only the contract exists in this phase; the review workflow and its table come later, which is
 * why proposals are not persisted yet.
 */
public record MemoryUpdateProposal(
    UUID id,
    UUID projectId,
    BrainEntryType proposedType,
    String title,
    String content,
    String source,
    MemoryProposalStatus status,
    Instant proposedAt) {

  public static MemoryUpdateProposal proposed(
      UUID projectId, BrainEntryType type, String title, String content, String source) {
    return new MemoryUpdateProposal(
        UUID.randomUUID(),
        projectId,
        type,
        title,
        content,
        source,
        MemoryProposalStatus.PENDING,
        Instant.now());
  }

  /** The entry this proposal would become if accepted. Never called before validation. */
  public BrainEntry toEntry() {
    if (status != MemoryProposalStatus.ACCEPTED) {
      throw new IllegalStateException("Only an accepted proposal can enter the Project Brain");
    }
    return new BrainEntry(projectId, proposedType, title, content, source);
  }

  public MemoryUpdateProposal accept() {
    return withStatus(MemoryProposalStatus.ACCEPTED);
  }

  public MemoryUpdateProposal reject() {
    return withStatus(MemoryProposalStatus.REJECTED);
  }

  private MemoryUpdateProposal withStatus(MemoryProposalStatus newStatus) {
    return new MemoryUpdateProposal(
        id, projectId, proposedType, title, content, source, newStatus, proposedAt);
  }
}
