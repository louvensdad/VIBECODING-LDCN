package com.vibecode.usage.domain;

/**
 * How much a credit figure can be trusted.
 *
 * <p>The distinction is a product rule, not a detail: the workspace must never show an estimate as
 * if it were a real balance.
 */
public enum CreditConfidence {
  /** Read from the provider. A fact. */
  EXACT,
  /** Derived from observed usage. A calculation, and it can be wrong. */
  ESTIMATED,
  /** No basis to state anything. Show nothing rather than a number. */
  UNKNOWN
}
