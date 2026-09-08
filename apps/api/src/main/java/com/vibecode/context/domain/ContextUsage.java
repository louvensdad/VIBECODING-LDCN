package com.vibecode.context.domain;

/**
 * What a set of items actually costs, in the three dimensions that can be counted exactly.
 *
 * <p>Every figure here is a measurement, not a projection. The estimate — see {@link
 * EstimatedTokenCount} — is derived from the character count and is kept in a type that says so, so
 * that an exact number and a guess never sit side by side as if they were the same thing.
 *
 * @param items how many items
 * @param characters total {@code content} length in Java characters
 * @param bytes total {@code content} length in UTF-8 bytes
 */
public record ContextUsage(int items, long characters, long bytes) {

  public static final ContextUsage EMPTY = new ContextUsage(0, 0L, 0L);

  public ContextUsage {
    if (items < 0 || characters < 0 || bytes < 0) {
      throw new IllegalArgumentException("Usage cannot be negative");
    }
  }

  public ContextUsage plus(ContextItem item) {
    return new ContextUsage(
        items + 1, characters + item.characterCount(), bytes + item.byteCount());
  }

  /** A guess derived from the exact character count, and marked as a guess by its type. */
  public EstimatedTokenCount estimatedTokens() {
    return EstimatedTokenCount.fromCharacters(characters);
  }
}
