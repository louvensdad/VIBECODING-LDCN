package com.vibecode.context.domain;

/**
 * The two answers a context policy is allowed to give about one candidate item.
 *
 * <p>There is deliberately no third constant. No {@code UNDECIDED}, no {@code REVIEW}, no {@code
 * UNKNOWN}: a candidate that nothing has decided about is denied, and saying so with a value that
 * reads as "not yet decided" would invite a later step to treat it as "probably fine". The absence
 * of a rule is a {@link #DENY}, and it is expressed as one — see {@code ContextPolicy}.
 *
 * <p>This enum is never persisted. Every stored {@code context_pack_items} row is an {@link #ALLOW}
 * by construction — a denied item is not written at all — so a column holding this value would say
 * the same word on every row and carry no information.
 */
public enum ContextAdmissionDecision {

  /** The item may enter a pack. Only ever produced with a rule id and an explanation. */
  ALLOW,

  /**
   * The item may not enter a pack. The default answer, reached both by an explicit rule that says
   * no and by no rule speaking at all.
   */
  DENY
}
