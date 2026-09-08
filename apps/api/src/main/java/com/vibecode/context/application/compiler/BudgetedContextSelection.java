package com.vibecode.context.application.compiler;

import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.domain.ContextUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * Fits admitted items under a budget, at item boundaries and nowhere else.
 *
 * <p><b>No partial truncation, ever.</b> An item that does not fit is left out whole. Cutting one
 * in half would produce text that reads as complete and is not: a rule with its exception removed,
 * an error message without its cause, a constraint whose second clause is missing. A reader cannot
 * tell a truncated item from a short one, and neither can a model. Half an item is worse than no
 * item, because no item is visibly absent.
 *
 * <p><b>Deterministic, and not by accident.</b> The input is already in {@link
 * AdmittedContextItem#CANONICAL_ORDER}, which is fixed by the domain and consults no clock, hash or
 * insertion order. This walks it once, keeping a running {@link ContextUsage}, and admits an item
 * only if the total <em>after</em> adding it still fits in all three dimensions. Same items, same
 * budget, same result, every run.
 *
 * <p><b>An item that does not fit is skipped, not a stopping point.</b> The walk continues, so a
 * single oversized item costs only itself and the smaller items behind it still get in. The
 * alternative — stop at the first item that does not fit — would let one long error message empty
 * the rest of a pack, and would make what a pack contains depend on the size of an item that is not
 * in it. Skipping is not a ranking: nothing here decides that a later item is worth more than the
 * one passed over, only that it fits.
 *
 * <p>The measurement is of the <b>redacted</b> content, because by the time an item reaches here it
 * has been through {@code ContextRedaction} and the redacted text is what would actually leave the
 * platform. A budget enforced against text nobody will see is not a budget.
 *
 * <p>This class does not select <em>which</em> context is worth having — the policy already did
 * that, deny-by-default, and every item arriving here has been admitted by a named rule. All that
 * is left is arithmetic.
 */
public final class BudgetedContextSelection {

  private BudgetedContextSelection() {}

  /**
   * The prefix-with-gaps of {@code ordered} that fits under {@code budget}, in the same order.
   *
   * @param ordered admitted items; sorted here defensively, so a caller that has not sorted cannot
   *     produce a different pack from one that has
   * @param budget the ceiling, in all three counted dimensions
   */
  public static List<AdmittedContextItem> select(
      List<AdmittedContextItem> ordered, ContextBudget budget) {
    if (ordered == null) {
      throw new IllegalArgumentException("There is nothing to select from");
    }
    if (budget == null) {
      throw new IllegalArgumentException("Selection needs a budget to hold items to");
    }

    List<AdmittedContextItem> candidates = new ArrayList<>(ordered);
    candidates.sort(AdmittedContextItem.CANONICAL_ORDER);

    List<AdmittedContextItem> selected = new ArrayList<>(candidates.size());
    ContextUsage running = ContextUsage.EMPTY;
    for (AdmittedContextItem candidate : candidates) {
      ContextUsage next = running.plus(candidate.item());
      if (!budget.admits(next)) {
        continue;
      }
      selected.add(candidate);
      running = next;
    }
    return List.copyOf(selected);
  }
}
