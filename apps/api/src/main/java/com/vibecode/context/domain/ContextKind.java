package com.vibecode.context.domain;

/**
 * What a context item <em>is</em>, as opposed to where it came from.
 *
 * <p>Source type answers "which record produced this"; kind answers "what does a reader do with it".
 * The two are separate because one source can yield different kinds — a task record produces both an
 * objective and its constraints — and because ordering wants a second, content-shaped key once the
 * source is equal.
 *
 * <p>Named {@code ContextKind} rather than {@code ContextType} to avoid reading as a sibling of
 * {@link ContextSourceType}; the two would be easy to confuse at a call site where both appear.
 */
public enum ContextKind {

  /** What the work is trying to achieve. */
  OBJECTIVE(10),

  /** A boundary the work must respect. */
  CONSTRAINT(20),

  /** A rule the project has bound itself to. */
  RULE(30),

  /** A decision already taken, and why. */
  DECISION(40),

  /** Something the system must do. */
  REQUIREMENT(50),

  /** A structural fact about how the system is built. */
  ARCHITECTURE(60),

  /** Where things currently stand. */
  STATE(70),

  /** An observed, recorded fact about work that was done. */
  EVIDENCE(80),

  /** A known open problem. */
  DEFECT(90),

  /** A security observation. Describes a location or a posture, never a secret. */
  SECURITY_NOTE(100);

  private final int orderingRank;

  ContextKind(int orderingRank) {
    this.orderingRank = orderingRank;
  }

  /** The stable sort weight of this kind. Explicit for the same reason as {@link
   * ContextSourceType#orderingRank()}: ordinals shift under edits, sort keys must not. */
  public int orderingRank() {
    return orderingRank;
  }
}
