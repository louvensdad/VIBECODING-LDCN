package com.vibecode.brain.application;

import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.brain.domain.MemoryProposalStatus;
import com.vibecode.brain.domain.MemoryProposalTrigger;
import com.vibecode.brain.domain.MemoryUpdateProposal;
import com.vibecode.brain.infrastructure.BrainEntryRepository;
import com.vibecode.brain.infrastructure.MemoryUpdateProposalRepository;
import com.vibecode.project.application.ProjectService;
import com.vibecode.shared.domain.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queue between what happened and what the project officially remembers.
 *
 * <p>The workflow raises proposals as events occur — a task completed, an error found, an error
 * resolved. None of them touches the Project Brain. A person accepts a proposal, and only then does
 * an entry exist. That gap is the whole point: the platform observes, the user decides what the
 * project believes.
 */
@Service
@Transactional
public class MemoryProposalService {

  private final MemoryUpdateProposalRepository proposals;
  private final BrainEntryRepository entries;
  private final ProjectService projects;

  public MemoryProposalService(
      MemoryUpdateProposalRepository proposals,
      BrainEntryRepository entries,
      ProjectService projects) {
    this.proposals = proposals;
    this.entries = entries;
    this.projects = projects;
  }

  public MemoryUpdateProposal propose(
      UUID projectId,
      MemoryProposalTrigger trigger,
      BrainEntryType proposedType,
      String title,
      String content,
      String source) {
    return proposals.save(
        new MemoryUpdateProposal(projectId, trigger, proposedType, title, content, source));
  }

  @Transactional(readOnly = true)
  public List<MemoryUpdateProposal> list(UUID projectId) {
    projects.requireExisting(projectId);
    return proposals.findByProjectIdOrderByCreatedAtDesc(projectId);
  }

  @Transactional(readOnly = true)
  public List<MemoryUpdateProposal> listPending(UUID projectId) {
    projects.requireExisting(projectId);
    return proposals.findByProjectIdAndStatusOrderByCreatedAtDesc(
        projectId, MemoryProposalStatus.PENDING);
  }

  /** Accepts a proposal and writes the resulting entry. This is the only automated write path. */
  public BrainEntry accept(UUID projectId, UUID proposalId) {
    MemoryUpdateProposal proposal = require(projectId, proposalId);
    proposal.accept();
    BrainEntry entry = entries.save(proposal.toEntry());
    proposal.recordResultingEntry(entry.getId());
    return entry;
  }

  public MemoryUpdateProposal reject(UUID projectId, UUID proposalId) {
    MemoryUpdateProposal proposal = require(projectId, proposalId);
    proposal.reject();
    return proposal;
  }

  private MemoryUpdateProposal require(UUID projectId, UUID proposalId) {
    MemoryUpdateProposal proposal =
        proposals
            .findById(proposalId)
            .orElseThrow(() -> new ResourceNotFoundException("Proposal not found: " + proposalId));
    if (!proposal.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("Proposal not found: " + proposalId);
    }
    return proposal;
  }
}
