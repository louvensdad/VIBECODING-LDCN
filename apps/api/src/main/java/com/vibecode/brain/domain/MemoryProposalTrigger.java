package com.vibecode.brain.domain;

/**
 * What raised a memory proposal.
 *
 * <p>Every proposal names the event behind it, so a reviewer can tell an observation the platform
 * derived from state apart from something a model asserted.
 */
public enum MemoryProposalTrigger {
  TASK_COMPLETED,
  ERROR_FOUND,
  ERROR_RESOLVED,
  CURRENT_STATE_CHANGED,
  NEXT_STEP_CHANGED,
  /** Raised by a person, or transcribed from a model answer. */
  MANUAL
}
