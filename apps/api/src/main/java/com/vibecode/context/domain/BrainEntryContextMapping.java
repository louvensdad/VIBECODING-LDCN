package com.vibecode.context.domain;

import com.vibecode.brain.domain.BrainEntryType;

/**
 * The one place that says what a brain entry means once it becomes context.
 *
 * <p>This is the single file in {@code context.domain} permitted to import another module. Context
 * sits above brain, so the direction context → brain is the right way round, and confining the
 * import to one greppable class keeps the rest of the domain a leaf. Nothing else here may import
 * {@code com.vibecode.brain}.
 *
 * <p><b>The switch has no {@code default} branch, on purpose.</b> An exhaustive switch over {@link
 * BrainEntryType} makes a missing case a compile error: delete a mapping, or add a fourteenth entry
 * type in the brain module, and this file stops compiling until someone decides what the new
 * knowledge means as context. A {@code default} — or an {@code UNKNOWN} kind to fall back to — would
 * turn that decision into silence, and the entry would enter a pack wearing a meaning nobody chose.
 * A test guarding the same property can be deleted by whoever finds it inconvenient; a compile error
 * cannot be ignored, only answered.
 *
 * <p>The mapping is deliberately name-for-name. A collector must never file a {@code TECHNOLOGY}
 * entry as {@code ARCHITECTURE} because that is the nearest available constant: the item's
 * provenance would then be a claim the domain cannot check and a reader cannot disprove.
 */
public final class BrainEntryContextMapping {

  private BrainEntryContextMapping() {}

  /**
   * The kind an entry of this type carries into a pack.
   *
   * <p>The entry's <em>source</em> is always {@link ContextSourceType#BRAIN_ENTRY} — the type says
   * what the entry means, not where it came from.
   */
  public static ContextKind kindOf(BrainEntryType entryType) {
    if (entryType == null) {
      throw new IllegalArgumentException("A brain entry type is required to derive a context kind");
    }
    return switch (entryType) {
      case VISION -> ContextKind.VISION;
      case REQUIREMENT -> ContextKind.REQUIREMENT;
      case ARCHITECTURE -> ContextKind.ARCHITECTURE;
      case TECHNOLOGY -> ContextKind.TECHNOLOGY;
      case DECISION -> ContextKind.DECISION;
      case RULE -> ContextKind.RULE;
      case CURRENT_STATE -> ContextKind.CURRENT_STATE;
      case COMPLETED_STEP -> ContextKind.COMPLETED_STEP;
      case ERROR -> ContextKind.ERROR;
      case SOLUTION -> ContextKind.SOLUTION;
      case NEXT_STEP -> ContextKind.NEXT_STEP;
      case PROMPT_RESULT -> ContextKind.PROMPT_RESULT;
      case NOTE -> ContextKind.NOTE;
    };
  }
}
