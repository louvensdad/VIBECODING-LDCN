package com.vibecode.context.domain;

/**
 * Where a context item was read from — the record's origin, never its meaning.
 *
 * <p><b>Availability is not inclusion.</b> A constant here says only that the engine <em>may</em>
 * draw an item from that source. It never says the source will appear in a pack. Selection is
 * deny-by-default and decided by policy elsewhere; nothing in this enum grants entry, and a reader
 * who treats the list below as "what a pack contains" has misread it.
 *
 * <p><b>This axis is independent of {@link ContextKind}.</b> Origin and meaning are two questions
 * and this enum answers only the first. All official memory arrives through the single {@link
 * #BRAIN_ENTRY} source whatever it says; what it says is the item's kind. A brain entry recording a
 * technology choice is {@code sourceType=BRAIN_ENTRY, sourceId=<entry id>, kind=TECHNOLOGY} — there
 * is deliberately no {@code BRAIN_ARCHITECTURE} or {@code BRAIN_DECISION} constant to tempt a
 * collector into folding the meaning into the origin. Doing so would either lose the entry types
 * that have no matching constant or file them under a source they never came from, and provenance
 * that misstates the origin is worse than no provenance: it is confidently wrong.
 *
 * <p>Because the axes are independent, a word can appear on both. {@link #CURRENT_STATE} here is the
 * computed project state as a place to read from, while {@code ContextKind.CURRENT_STATE} is what a
 * record says about where things stand — so {@code sourceType=BRAIN_ENTRY, kind=CURRENT_STATE}
 * (state as remembered) and {@code sourceType=CURRENT_STATE, kind=CURRENT_STATE} (state as computed)
 * are both meaningful and are not the same item. The same holds for {@link #ACTIVE_ERRORS} against
 * {@code ContextKind.ERROR}.
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

  /** The computed current state of the project — where it stands, as the system works it out. */
  CURRENT_STATE(20),

  /**
   * One entry of official project memory, whatever kind of knowledge it holds. The entry's own type
   * becomes the item's {@link ContextKind}; see {@link BrainEntryContextMapping}.
   */
  BRAIN_ENTRY(30),

  /** The plan as a whole. */
  ROADMAP(40),

  /** The phase currently in progress. */
  CURRENT_PHASE(50),

  /** The task the work is actually about. */
  CURRENT_TASK(60),

  /** What "done" means for the current task. */
  ACCEPTANCE_CRITERIA(70),

  /** The most recent recorded evidence of work. */
  LATEST_EVIDENCE(80),

  /** The most recent analysis of produced output. */
  LATEST_OUTPUT_ANALYSIS(90),

  /** Problems known to be open. */
  ACTIVE_ERRORS(100),

  /**
   * The security posture, summarised. A summary only — findings describe locations, never the
   * offending value, and no secret material reaches this domain by any route.
   */
  SECURITY_SUMMARY(110);

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
