package com.vibecode.task.domain;

/**
 * Whether an acceptance criterion has been met.
 *
 * <p>A criterion is only ever moved by an explicit decision — a person confirming it, or a check
 * that actually tests that criterion. It is deliberately not inferred from a passing build: a green
 * build says the code compiles, not that "the login flow rejects expired tokens" was verified.
 */
public enum CriterionStatus {
  PENDING,
  SATISFIED,
  FAILED,
  UNKNOWN
}
