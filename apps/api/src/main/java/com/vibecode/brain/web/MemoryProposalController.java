package com.vibecode.brain.web;

import com.vibecode.brain.application.MemoryProposalService;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.brain.domain.MemoryProposalStatus;
import com.vibecode.brain.domain.MemoryProposalTrigger;
import com.vibecode.brain.domain.MemoryUpdateProposal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The review queue for proposed memory.
 *
 * <p>The workflow raises proposals as things happen; nothing reaches official memory until someone
 * accepts one here. Accepting is the only automated write path into the Project Brain, and it still
 * requires a person to trigger it.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/brain/proposals")
public class MemoryProposalController {

  private final MemoryProposalService proposals;

  public MemoryProposalController(MemoryProposalService proposals) {
    this.proposals = proposals;
  }

  @GetMapping
  public List<ProposalResponse> list(
      @PathVariable UUID projectId,
      @RequestParam(name = "pendingOnly", defaultValue = "false") boolean pendingOnly) {
    List<MemoryUpdateProposal> found =
        pendingOnly ? proposals.listPending(projectId) : proposals.list(projectId);
    return found.stream().map(ProposalResponse::from).toList();
  }

  @PostMapping("/{proposalId}/accept")
  public BrainEntryResponse accept(
      @PathVariable UUID projectId, @PathVariable UUID proposalId) {
    return BrainEntryResponse.from(proposals.accept(projectId, proposalId));
  }

  @PostMapping("/{proposalId}/reject")
  public ProposalResponse reject(@PathVariable UUID projectId, @PathVariable UUID proposalId) {
    return ProposalResponse.from(proposals.reject(projectId, proposalId));
  }

  public record ProposalResponse(
      UUID id,
      MemoryProposalTrigger trigger,
      BrainEntryType proposedType,
      String title,
      String content,
      String source,
      MemoryProposalStatus status,
      UUID resultingEntryId,
      Instant createdAt,
      Instant reviewedAt) {

    static ProposalResponse from(MemoryUpdateProposal proposal) {
      return new ProposalResponse(
          proposal.getId(),
          proposal.getTrigger(),
          proposal.getProposedType(),
          proposal.getTitle(),
          proposal.getContent(),
          proposal.getSource(),
          proposal.getStatus(),
          proposal.getResultingEntryId(),
          proposal.getCreatedAt(),
          proposal.getReviewedAt());
    }
  }
}
