package com.vibecode.context.domain;

import java.util.Objects;

/**
 * A guess at how many tokens some text would become — labelled as a guess, permanently.
 *
 * <p>This phase calls no provider and runs no tokenizer, so a real token count is not available
 * here. The honest options were to omit the number or to make it impossible to mistake for a fact;
 * the number is useful enough for a warning in the Context Inspector to be worth keeping, so the
 * type carries the caveat instead of the caller remembering it.
 *
 * <p><b>Why the constructor is private.</b> One module away, {@code usage.domain.UsageEvent} holds
 * {@code inputTokens} and {@code outputTokens} as exact longs reported by a provider. Those are the
 * numbers this type must never be confused with. A public constructor would let a caller write
 * {@code new EstimatedTokenCount(exactTokens, "exact count from provider tokenizer")} and produce an
 * object whose type says "estimate" and whose contents say otherwise — the label would be permanent
 * in name only. The factories below fix the heuristic to something this phase can actually perform,
 * so an estimate can only ever describe how it was guessed.
 *
 * <p>The value is reachable only through {@link #estimatedTokens()}, {@link #isExact()} answers
 * {@code false}, and {@link #toString()} says "estimate" out loud.
 *
 * <p><b>It is not a budget limit.</b> {@link ContextBudget} deliberately admits no token dimension:
 * items, characters and bytes can all be counted exactly, and a limit enforced against a heuristic
 * would be a limit that is wrong by an unknown amount. Provider-specific tokenization is out of
 * scope for this phase and must not be added here.
 */
public final class EstimatedTokenCount {

  /**
   * The crude divisor this phase uses. It is roughly right for English prose and visibly wrong for
   * dense code or non-Latin scripts, which is precisely why the result is never called a count.
   */
  private static final int CHARACTERS_PER_TOKEN = 4;

  private static final String CHARACTER_HEURISTIC =
      "characters / " + CHARACTERS_PER_TOKEN + " (no provider tokenizer in this phase)";

  private final long estimatedTokens;
  private final String heuristic;

  private EstimatedTokenCount(long estimatedTokens, String heuristic) {
    this.estimatedTokens = estimatedTokens;
    this.heuristic = heuristic;
  }

  /**
   * Estimates from a character count, in the UTF-16 code units {@link
   * ContextItem#characterCount()} counts. Rounds up, so the estimate errs towards caution.
   */
  public static EstimatedTokenCount fromCharacters(long characters) {
    if (characters < 0) {
      throw new IllegalArgumentException("Character count cannot be negative");
    }
    long estimate = (characters + CHARACTERS_PER_TOKEN - 1) / CHARACTERS_PER_TOKEN;
    return new EstimatedTokenCount(estimate, CHARACTER_HEURISTIC);
  }

  /** The estimate. Never a measurement — see the class javadoc for why that is enforceable. */
  public long estimatedTokens() {
    return estimatedTokens;
  }

  /** The rule that produced the number, so a reader can judge how wrong it might be. */
  public String heuristic() {
    return heuristic;
  }

  /**
   * Always {@code false}, and it always will be. It exists so code that consumes an estimate can
   * state the check rather than assume the answer.
   */
  public boolean isExact() {
    return false;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof EstimatedTokenCount that)) {
      return false;
    }
    return estimatedTokens == that.estimatedTokens && heuristic.equals(that.heuristic);
  }

  @Override
  public int hashCode() {
    return Objects.hash(estimatedTokens, heuristic);
  }

  @Override
  public String toString() {
    return "~" + estimatedTokens + " tokens (estimate, " + heuristic + ")";
  }
}
