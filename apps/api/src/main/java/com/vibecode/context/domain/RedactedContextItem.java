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
 * it holds went through the redactor — it records <em>which boundary</em> produced it. It is a
 * marker of provenance-through-redaction, not a proof of it, and saying otherwise would be
 * promising more than the code delivers.
 *
 * <p>What the build does enforce, exactly: {@code ContextModuleArchitectureTest} allows four
 * production classes in the whole application — not merely in {@code ..context..} — to depend on
 * this type at all: this one, {@code ContextRedaction}, {@code ContextPackItemEntity} and {@code
 * AdmittedContextItem}. The four are excluded by fully qualified name and not by {@code
 * belongToAnyOf}, which would have exempted anything nested inside them; that was the second
 * bypass, and a public nested class in {@code AdmittedContextItem.java} minting by method
 * reference passed every rule until it was fixed. So no fifth class may declare this type, call a
 * method on it, or reference one of its methods — which is every way of obtaining one that does
 * not already have one in hand. Passing along a wrapper somebody else made is invisible to the
 * rule and harmless: making one over raw content requires reaching this class, and reaching it is
 * what the fence sees. An earlier version of that rule fenced calls and declared return types instead, and
 * a review walked through it with a {@code Function} field holding a method reference: no
 * reflection, and the fixture reached the table. The fence is a dependency rule now because
 * enumerating access kinds is open by construction and an allowlist is not.
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
   * <p>Reachable only from {@code ContextRedaction}, and that is a build rule rather than a
   * language one: a package-private constructor cannot express "one other package may call this"
   * without a JPMS module, and this project has none. Two rules in {@code
   * ContextModuleArchitectureTest} hold it — the dependency allowlist, which is the boundary
   * because it covers every way of naming this class, and a narrower rule on direct calls, which
   * exists to fail with a sentence about redaction rather than about dependencies. Both bite on the
   * build, which is the same place a broken compile would.
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
   * {@code context_pack_items} that could hold a pre-redaction value, and the only write path is
   * one whose types demand a redacted item. A hand-edited row therefore comes back exactly as
   * edited, and that is the accepted cost of being able to load a snapshot at all.
   *
   * <p>"The only write path" is a claim about production code that a build rule keeps true, not a
   * property of the schema. A class that could name this type could hand a launderer a wrapper over
   * anything, and for one commit a method reference in the compiler package did exactly that — see
   * the class javadoc. The dependency allowlist is what closed it; without that rule this paragraph
   * would be wishful.
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
