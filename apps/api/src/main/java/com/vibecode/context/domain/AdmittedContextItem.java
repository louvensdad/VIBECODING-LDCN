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
 * <p>{@code item} holds the <b>redacted</b> content by the time it reaches here. Redaction runs
 * before admission is paired with anything, before measurement and before persistence; this type
 * does not perform it and cannot check that it happened, so it makes no claim that it did. What it
 * does guarantee is that whatever content it carries is the content the digest covers and the
 * content the database stores — there is no second copy anywhere for a raw value to survive in.
 *
 * @param item the item as it would be rendered, already redacted
 * @param admission the decision that admitted it; mandatory, and must be an allow
 */
public record AdmittedContextItem(ContextItem item, ContextAdmission admission) {

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
    if (item == null) {
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
              + item.id()
              + " was denied by rule "
              + admission.policyRuleId()
              + " and must not be materialised — a denied item does not enter a pack, and its"
              + " content is not stored so it can be shown later");
    }
  }

  public String id() {
    return item.id();
  }
}
