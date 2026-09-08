package com.vibecode.context.domain;

/**
 * A guess at how many tokens some text would become — labelled as a guess, permanently.
 *
 * <p>This phase calls no provider and runs no tokenizer, so a real token count is not available
 * here. The honest options were to omit the number or to make it impossible to mistake for a fact;
 * the number is useful enough for a warning in the Context Inspector to be worth keeping, so the
 * type carries the caveat instead of the caller remembering it. The value is only reachable through
 * {@link #estimatedTokens()}, {@link #toString()} says "estimate" out loud, and there is no
 * accessor, factory or field whose name would survive being copied into a place that means "exact".
 *
 * <p><b>It is not a budget limit.</b> {@link ContextBudget} deliberately admits no token dimension:
 * items, characters and bytes can all be counted exactly, and a limit enforced against a heuristic
 * would be a limit that is wrong by an unknown amount. Provider-specific tokenization is out of
 * scope for this phase and must not be added here.
 *
 * @param estimatedTokens the estimate; never a measurement
 * @param heuristic the rule that produced it, so a reader can judge how wrong it might be
 */
public record EstimatedTokenCount(long estimatedTokens, String heuristic) {

  /**
   * The crude divisor this phase uses. It is roughly right for English prose and visibly wrong for
   * dense code or non-Latin scripts, which is precisely why the result is never called a count.
   */
  private static final int CHARACTERS_PER_TOKEN = 4;

  private static final String CHARACTER_HEURISTIC =
      "characters / " + CHARACTERS_PER_TOKEN + " (no provider tokenizer in this phase)";

  public EstimatedTokenCount {
    if (estimatedTokens < 0) {
      throw new IllegalArgumentException("A token estimate cannot be negative");
    }
    if (heuristic == null || heuristic.isBlank()) {
      throw new IllegalArgumentException(
          "A token estimate must name the heuristic that produced it, or a reader cannot judge it");
    }
  }

  /** Estimates from a character count. Rounds up, so the estimate errs towards caution. */
  public static EstimatedTokenCount fromCharacters(long characters) {
    if (characters < 0) {
      throw new IllegalArgumentException("Character count cannot be negative");
    }
    long estimate = (characters + CHARACTERS_PER_TOKEN - 1) / CHARACTERS_PER_TOKEN;
    return new EstimatedTokenCount(estimate, CHARACTER_HEURISTIC);
  }

  /**
   * Always {@code false}, and it always will be. It exists so code that consumes an estimate can
   * state the check rather than assume the answer.
   */
  public boolean isExact() {
    return false;
  }

  @Override
  public String toString() {
    return "~" + estimatedTokens + " tokens (estimate, " + heuristic + ")";
  }
}
