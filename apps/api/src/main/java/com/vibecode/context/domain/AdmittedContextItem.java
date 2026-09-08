package com.vibecode.context.domain;

import java.util.Comparator;

/**
 * An item paired with the decision that let it in — the only form in which an item can be
 * materialised into a pack.
 *
 * <p><b>This type exists to make a convention structural.</b> The rule is that no item reaches a
 * pack without an explicit admission, and a rule of that kind kept by review lasts exactly as long
 * as the reviewer. Here the admission is a constructor argument, it is rejected when absent, and it
 * is rejected when it says {@link ContextAdmissionDecision#DENY}. {@link CompiledContextPack} is
 * built from a list of these and from nothing else — there is no constructor anywhere that takes
 * bare {@link ContextItem}s — so "an unadmitted item in a pack" is not a bug that could slip
 * through, it is a state with no way to be expressed.
 *
 * <p><b>The item arrives as a {@link RedactedContextItem}, not as a bare {@link ContextItem}.</b>
 * That is the second convention this type makes structural, and for the same reason as the first:
 * redaction used to be a stage the compiler happened to run, so a caller assembling a pack by hand
 * skipped it and nothing objected. Now the parameter type objects, at compile time, everywhere.
 * This type still does not perform redaction and still cannot verify that it happened — see {@link
 * RedactedContextItem}, which is honest about the same limit — but a raw {@code ContextItem} no
 * longer has a route in.
 *
 * <p>Whatever content it carries is the content the digest covers and the content the database
 * stores. There is no second copy anywhere for a raw value to survive in.
 *
 * @param redactedItem the item as it would be rendered, marked by the boundary that produced it
 * @param admission the decision that admitted it; mandatory, and must be an allow
 */
public record AdmittedContextItem(RedactedContextItem redactedItem, ContextAdmission admission) {

  /**
   * The canonical order, delegating entirely to {@link ContextItem#CANONICAL_ORDER}.
   *
   * <p>The admission contributes no sort key on purpose. Ordering is a property of what the items
   * are, not of which rule let them in: sorting by rule id would mean that renaming a rule
   * reshuffles every pack compiled afterwards, and two packs holding the same context would read
   * differently for a reason that has nothing to do with their content.
   */
  public static final Comparator<AdmittedContextItem> CANONICAL_ORDER =
      Comparator.comparing(AdmittedContextItem::item, ContextItem.CANONICAL_ORDER);

  public AdmittedContextItem {
    if (redactedItem == null) {
      throw new IllegalArgumentException("An admitted item must have an item");
    }
    if (admission == null) {
      throw new IllegalArgumentException(
          "An item cannot be materialised without an admission: nothing enters a pack that nobody"
              + " can say why is there");
    }
    if (!admission.isAllowed()) {
      throw new IllegalArgumentException(
          "Item "
              + redactedItem.item().id()
              + " was denied by rule "
              + admission.policyRuleId()
              + " and must not be materialised — a denied item does not enter a pack, and its"
              + " content is not stored so it can be shown later");
    }
  }

  /**
   * The redacted item itself.
   *
   * <p>Kept as a method beside the {@code redactedItem} component so that every reader of a pack —
   * the canonical payload, the entity, the usage count — goes on saying {@code admitted.item()}.
   * The wrapper exists to constrain what may be <em>constructed</em>; making every consumer unwrap
   * it by hand would have added noise without adding a guarantee.
   */
  public ContextItem item() {
    return redactedItem.item();
  }

  public String id() {
    return item().id();
  }
}
