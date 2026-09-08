package com.vibecode.context.domain;

import java.util.Objects;

/**
 * An item that has been through redaction — the only form in which content may be materialised into
 * a pack, and therefore the only form that reaches the database.
 *
 * <p><b>Why this type exists.</b> Redaction used to be a pipeline stage and nothing more: {@link
 * CompiledContextPack} redacted nothing, the compiler redacted on the way in, and a caller who
 * assembled a pack by hand simply skipped the step. The guarantee was discipline. This type makes
 * it a type error instead — {@link AdmittedContextItem} takes one of these and refuses a bare
 * {@link ContextItem}, so the forbidden flow
 *
 * <pre>raw String &rarr; ContextItem &rarr; CompiledContextPack &rarr; persistence</pre>
 *
 * <p>does not compile anywhere in the codebase. The permitted flow is the pipeline's own:
 *
 * <pre>raw candidate &rarr; ContextRedaction &rarr; RedactedContextItem &rarr; CompiledContextPack
 * &rarr; persistence</pre>
 *
 * <p><b>It wraps the whole item, not just the content.</b> A content-only wrapper would have left
 * the label — which is redacted, stored, and displayed — arriving at persistence as a bare {@code
 * String}, so the boundary would have covered the field nobody would have leaked through and missed
 * the one that is short enough to be read as harmless. One wrapper around the whole redacted item is
 * both the smaller construction and the wider guarantee.
 *
 * <p><b>What it does not claim.</b> It does not redact; {@code ContextRedaction} does, and the
 * domain cannot see the Guardian's redactor by design. So this type does not verify that the text
 * it holds went through the redactor — it records <em>which boundary</em> produced it, and the build
 * enforces that only two callers exist. It is a marker of provenance-through-redaction, not a proof
 * of it, and saying otherwise would be promising more than the code delivers.
 *
 * <p><b>The limit, stated plainly.</b> The private constructor is reachable by reflection, as the
 * private constructor of every type in Java is. The claim defended here is that no ordinary
 * production path can build one without going through redaction or through a row we ourselves wrote
 * — not that the JVM makes it impossible. {@code ContextSafeContentBypassTest} demonstrates both
 * halves of that, including the reflective forge, rather than leaving the limit implied.
 */
public final class RedactedContextItem {

  private final ContextItem item;

  private RedactedContextItem(ContextItem item) {
    if (item == null) {
      throw new IllegalArgumentException("There is no item to mark as redacted");
    }
    this.item = item;
  }

  /**
   * The one mint on the materialisation path: the item as {@code ContextRedaction} returned it.
   *
   * <p>Callable only from {@code com.vibecode.context.application.redaction}, which is enforced by
   * {@code ContextModuleArchitectureTest} rather than by the compiler — a package-private
   * constructor cannot express "one other package" without a JPMS module, and this project has
   * none. The rule bites on the build, which is the same place a broken compile would.
   *
   * <p>Named for what it asserts. A method called {@code of} would read as a conversion and would
   * be reached for by the next person in a hurry; this one cannot be called without writing down
   * the claim being made.
   */
  public static RedactedContextItem producedByRedaction(ContextItem redacted) {
    return new RedactedContextItem(redacted);
  }

  /**
   * The rehydration mint: an item rebuilt from a stored row.
   *
   * <p><b>This is a different boundary from creation, and it is honest about being one.</b> Nothing
   * here re-runs redaction and nothing here can tell redacted text from raw. It trusts the row,
   * because the row was written by this application after redaction ran — there is no column in
   * {@code context_pack_items} that could hold a pre-redaction value, and no write path that could
   * fill one. A hand-edited row therefore comes back exactly as edited, and that is the accepted
   * cost of being able to load a snapshot at all.
   *
   * <p>Re-redacting on read would be worse than useless: the stored digest was taken over the text
   * as written, so a second pass that changed anything would produce a pack that no longer matched
   * its own digest, and a redactor whose patterns had since widened would do exactly that.
   *
   * <p>Callable only from {@code com.vibecode.context.infrastructure.persistence}, enforced the same
   * way as {@link #producedByRedaction(ContextItem)}.
   */
  public static RedactedContextItem rehydratedFromStorage(ContextItem stored) {
    return new RedactedContextItem(stored);
  }

  /** The item itself. Its content and label are the redacted ones; there is no other copy. */
  public ContextItem item() {
    return item;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof RedactedContextItem that && item.equals(that.item);
  }

  @Override
  public int hashCode() {
    return Objects.hash(item);
  }

  /** The item's handle and nothing else. Never its content, redacted or not. */
  @Override
  public String toString() {
    return "RedactedContextItem[item=" + item.id() + "]";
  }
}
