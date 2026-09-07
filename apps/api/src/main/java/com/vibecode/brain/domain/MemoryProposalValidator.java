package com.vibecode.brain.domain;

/**
 * The gate every proposed memory change must pass before it can become official.
 *
 * <p>Deliberately a port with no implementation in this phase: the rules (evidence required,
 * contradiction with existing entries, secret detection) are defined when the LLM integration
 * arrives. Declaring it now keeps the write path pointing at a checkpoint instead of at the Brain.
 */
public interface MemoryProposalValidator {

  ValidationOutcome validate(MemoryUpdateProposal proposal, ProjectBrain currentMemory);

  /** Why a proposal may or may not enter official memory. */
  record ValidationOutcome(boolean acceptable, String reason) {

    public static ValidationOutcome accept(String reason) {
      return new ValidationOutcome(true, reason);
    }

    public static ValidationOutcome reject(String reason) {
      return new ValidationOutcome(false, reason);
    }
  }
}
