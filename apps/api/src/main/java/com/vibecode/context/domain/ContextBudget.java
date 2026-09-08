package com.vibecode.context.domain;

/**
 * The ceiling a pack must fit under, expressed only in things that can be counted exactly.
 *
 * <p>All three dimensions are verifiable: items are counted, characters are counted, UTF-8 bytes are
 * counted. There is deliberately no token dimension. This phase has no provider tokenizer, so a
 * token limit could only be enforced against a heuristic — a limit that is wrong by an unknown
 * amount is worse than no limit, because it reads as authoritative. {@link EstimatedTokenCount}
 * exists for display and warnings, and is not accepted here.
 *
 * <p>A budget is a ceiling, not a target. The engine's rule is minimum necessary context: filling a
 * budget is not an achievement, and a pack that used a tenth of it because a tenth was what the task
 * needed is the better pack. Nothing in this type rewards filling it.
 *
 * <p>Named {@code ContextBudget} rather than {@code Budget} because {@code com.vibecode.usage}
 * already owns {@code ProjectBudget}, which is about money. These two must not be confused: this one
 * cannot be spent.
 *
 * @param maxItems the most items a pack may hold
 * @param maxCharacters the most content characters a pack may hold
 * @param maxBytes the most content bytes (UTF-8) a pack may hold
 */
public record ContextBudget(int maxItems, long maxCharacters, long maxBytes) {

  public ContextBudget {
    if (maxItems <= 0) {
      throw new IllegalArgumentException("A budget must allow at least one item");
    }
    if (maxCharacters <= 0) {
      throw new IllegalArgumentException("A budget must allow at least one character");
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("A budget must allow at least one byte");
    }
    if (maxBytes < maxCharacters) {
      // UTF-8 never encodes a character in less than one byte, so a byte ceiling below the
      // character ceiling can never bind and is a sign the two were transposed.
      throw new IllegalArgumentException(
          "A byte ceiling below the character ceiling is unreachable: "
              + maxBytes
              + " bytes < "
              + maxCharacters
              + " characters");
    }
  }

  /** Whether the given measured usage fits. Exact in all three dimensions. */
  public boolean admits(ContextUsage usage) {
    return usage.items() <= maxItems
        && usage.characters() <= maxCharacters
        && usage.bytes() <= maxBytes;
  }

  /**
   * The dimension that is over, or {@code null} when nothing is.
   *
   * <p>Returned as text because its only job is to explain a rejection to a person; callers deciding
   * anything should ask {@link #admits(ContextUsage)}.
   */
  public String firstBreach(ContextUsage usage) {
    if (usage.items() > maxItems) {
      return "items: " + usage.items() + " > " + maxItems;
    }
    if (usage.characters() > maxCharacters) {
      return "characters: " + usage.characters() + " > " + maxCharacters;
    }
    if (usage.bytes() > maxBytes) {
      return "bytes: " + usage.bytes() + " > " + maxBytes;
    }
    return null;
  }
}
