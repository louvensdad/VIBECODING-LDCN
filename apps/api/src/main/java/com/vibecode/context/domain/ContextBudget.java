package com.vibecode.context.domain;

import java.util.Optional;

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
 * <p>The two size dimensions are independent on purpose, and neither implies the other. A character
 * ceiling as a rough size guide alongside a much smaller byte ceiling as a transport limit is a
 * legitimate configuration and is accepted.
 *
 * @param maxItems the most items a pack may hold
 * @param maxCharacters the most content <b>UTF-16 code units</b> a pack may hold — the unit {@link
 *     String#length()} and {@link ContextItem#characterCount()} count, in which "😀" is 2, not 1
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
  }

  /** Whether the given measured usage fits. Exact in all three dimensions. */
  public boolean admits(ContextUsage usage) {
    return usage.items() <= maxItems
        && usage.characters() <= maxCharacters
        && usage.bytes() <= maxBytes;
  }

  /**
   * The dimension that is over, or empty when nothing is.
   *
   * <p>Returned as text because its only job is to explain a rejection to a person; callers deciding
   * anything should ask {@link #admits(ContextUsage)}.
   */
  public Optional<String> firstBreach(ContextUsage usage) {
    if (usage.items() > maxItems) {
      return Optional.of("items: " + usage.items() + " > " + maxItems);
    }
    if (usage.characters() > maxCharacters) {
      return Optional.of("characters: " + usage.characters() + " > " + maxCharacters);
    }
    if (usage.bytes() > maxBytes) {
      return Optional.of("bytes: " + usage.bytes() + " > " + maxBytes);
    }
    return Optional.empty();
  }
}
