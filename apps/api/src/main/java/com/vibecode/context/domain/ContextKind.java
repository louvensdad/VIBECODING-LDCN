package com.vibecode.context.domain;

/**
 * What a context item <em>means</em>, as opposed to where it came from.
 *
 * <p>Kind and {@link ContextSourceType} are independent axes and must not be collapsed into one.
 * Source type answers "which record produced this"; kind answers "what is a reader looking at". A
 * brain entry of type {@code TECHNOLOGY} is {@code sourceType=BRAIN_ENTRY, sourceId=<entry id>,
 * kind=TECHNOLOGY} — the source says where it was read, the kind says what it says. Encoding the
 * meaning in the source type instead would force a collector to claim the entry came from somewhere
 * it did not, and no later step could detect the lie.
 *
 * <p>Because the axes are independent, the same word appears on both and means different things.
 * {@code ContextSourceType.CURRENT_STATE} is the computed project state as a place to read from;
 * {@code ContextKind.CURRENT_STATE} is what a brain entry recorded about where things stand. An item
 * can legitimately be {@code sourceType=BRAIN_ENTRY, kind=CURRENT_STATE} — remembered state — or
 * {@code sourceType=CURRENT_STATE, kind=CURRENT_STATE} — computed state. Likewise {@code
 * ContextSourceType.ACTIVE_ERRORS} against {@code ContextKind.ERROR}.
 *
 * <p>Thirteen constants below carry the same names as {@code BrainEntryType} and exist so that every
 * kind of official memory has a faithful home. That correspondence is enforced by {@link
 * BrainEntryContextMapping}, not by this comment. The remaining four cover meanings no brain entry
 * type expresses, and each names the sources that produce it.
 *
 * <p>There is no {@code UNKNOWN} or {@code OTHER}. A fallback constant is how unmapped content
 * quietly enters a pack wearing the wrong meaning; if something has no kind here, the vocabulary is
 * wrong and must be changed deliberately.
 *
 * <p>Named {@code ContextKind} rather than {@code ContextType} to avoid reading as a sibling of
 * {@link ContextSourceType}; the two would be easy to confuse at a call site where both appear.
 */
public enum ContextKind {

  /**
   * What the work in front of the user is trying to achieve. Produced by {@code CURRENT_TASK},
   * {@code CURRENT_PHASE} and {@code ROADMAP} — not brain memory, which records the product-level
   * {@link #VISION} rather than the objective of the task at hand.
   */
  OBJECTIVE(10),

  /**
   * A boundary this particular piece of work must respect. Produced by {@code
   * ACCEPTANCE_CRITERIA} and by task scope. Distinct from {@link #RULE}: a rule is standing project
   * policy that outlives the task, a constraint binds only the work it was stated for.
   */
  CONSTRAINT(20),

  /** The product vision. */
  VISION(30),

  /** Something the system must do. */
  REQUIREMENT(40),

  /** A structural fact about how the system is built. */
  ARCHITECTURE(50),

  /** A specific technology the project uses. Deliberately not {@link #ARCHITECTURE}: naming a
   * library is not describing a structure, and merging the two would make the memory unsearchable. */
  TECHNOLOGY(60),

  /** A decision already taken, and why. */
  DECISION(70),

  /** A rule the project has bound itself to, standing until it is revoked. */
  RULE(80),

  /** Where things stand. As a kind this is what a record <em>says</em> about the state, whatever
   * source it was read from. */
  CURRENT_STATE(90),

  /** A step that is finished. */
  COMPLETED_STEP(100),

  /** What is meant to happen next. Distinct from {@link #CURRENT_STATE}: one is where the project
   * is, the other is where it is going. */
  NEXT_STEP(110),

  /**
   * An observed, recorded fact about work that was actually run. Produced by {@code
   * LATEST_EVIDENCE} and {@code LATEST_OUTPUT_ANALYSIS}. Distinct from {@link #COMPLETED_STEP},
   * which is memory asserting a step is done; evidence is the observation that supports such a
   * claim, and the two must stay separable or an unsupported claim reads like a verified one.
   */
  EVIDENCE(120),

  /** The recorded outcome of a prompt. Distinct from {@link #NOTE}: it is the result of a specific
   * exchange, not a free-standing remark. */
  PROMPT_RESULT(130),

  /** A problem. Whether it is still open is a question about the record, not about this kind. */
  ERROR(140),

  /** How a problem was resolved. Distinct from {@link #COMPLETED_STEP}: a solution answers an
   * {@link #ERROR}, a completed step answers a plan. */
  SOLUTION(150),

  /**
   * A security observation. Produced by {@code SECURITY_SUMMARY}. Distinct from {@link #NOTE} so
   * that security content cannot be lost among general remarks; it describes a location or a
   * posture, never a secret.
   */
  SECURITY_NOTE(160),

  /** Something worth remembering that none of the above describes. Not a fallback: a note is
   * chosen, never assigned because nothing else fitted. */
  NOTE(170);

  private final int orderingRank;

  ContextKind(int orderingRank) {
    this.orderingRank = orderingRank;
  }

  /**
   * The stable sort weight of this kind. Explicit for the same reason as {@link
   * ContextSourceType#orderingRank()}: ordinals shift under edits, sort keys must not.
   */
  public int orderingRank() {
    return orderingRank;
  }
}
