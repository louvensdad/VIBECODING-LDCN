package com.vibecode.brain.domain;

/**
 * The kinds of knowledge the official memory holds.
 *
 * <p>New types are additive: an entry type is persisted by name, so removing or renaming a constant
 * breaks stored memory.
 */
public enum BrainEntryType {
  VISION,
  REQUIREMENT,
  ARCHITECTURE,
  TECHNOLOGY,
  DECISION,
  RULE,
  CURRENT_STATE,
  COMPLETED_STEP,
  ERROR,
  SOLUTION,
  NEXT_STEP,
  PROMPT_RESULT,
  NOTE
}
