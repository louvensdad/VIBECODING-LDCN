package com.vibecode.context.domain;

/**
 * The kinds of recorded project state the context engine is permitted to read from.
 *
 * <p><b>Availability is not inclusion.</b> A constant here says only that the engine <em>may</em>
 * draw an item from that source. It never says the source will appear in a pack. Selection is
 * deny-by-default and decided by policy elsewhere; nothing in this enum grants entry, and a reader
 * who treats the list below as "what a pack contains" has misread it.
 *
 * <p>Every constant names an official record the Project Brain already owns. There is deliberately
 * no {@code FREE_TEXT}, {@code SCRATCH} or {@code MODEL_OUTPUT} constant: context that no recorded
 * state can vouch for has no provenance, and an item without provenance cannot be built.
 *
 * <p>The declared order is the canonical priority order, but it is not read from {@link #ordinal()}
 * — see {@link #orderingRank()} for why.
 */
public enum ContextSourceType {

  /** The project itself: its identity and its idea. */
  PROJECT(10),

  /** Where the project stands right now. */
  CURRENT_STATE(20),

  /** Brain memory: the product vision. */
  BRAIN_VISION(30),

  /** Brain memory: a recorded requirement. */
  BRAIN_REQUIREMENT(40),

  /** Brain memory: a recorded architectural fact. */
  BRAIN_ARCHITECTURE(50),

  /** Brain memory: a decision already taken, with its reasoning. */
  BRAIN_DECISION(60),

  /** Brain memory: a rule the project has bound itself to. */
  BRAIN_RULE(70),

  /** The plan as a whole. */
  ROADMAP(80),

  /** The phase currently in progress. */
  CURRENT_PHASE(90),

  /** The task the work is actually about. */
  CURRENT_TASK(100),

  /** What "done" means for the current task. */
  ACCEPTANCE_CRITERIA(110),

  /** The most recent recorded evidence of work. */
  LATEST_EVIDENCE(120),

  /** The most recent analysis of produced output. */
  LATEST_OUTPUT_ANALYSIS(130),

  /** Problems known to be open. */
  ACTIVE_ERRORS(140),

  /**
   * The security posture, summarised. A summary only — findings describe locations, never the
   * offending value, and no secret material reaches this domain by any route.
   */
  SECURITY_SUMMARY(150);

  private final int orderingRank;

  ContextSourceType(int orderingRank) {
    this.orderingRank = orderingRank;
  }

  /**
   * The stable sort weight of this source.
   *
   * <p>It is an explicit number rather than {@link #ordinal()} because ordinals move when a constant
   * is inserted, and a pack whose ordering silently changed with an unrelated edit would break the
   * determinism the whole engine rests on. The gaps of ten leave room to insert a source between two
   * existing ones without renumbering.
   */
  public int orderingRank() {
    return orderingRank;
  }
}
