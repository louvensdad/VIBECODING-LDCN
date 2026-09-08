package com.vibecode.context.application.redaction;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.RedactedContextItem;
import com.vibecode.guardian.domain.SensitiveDataRedactor;

/**
 * The one place a context item is redacted, and the only route content takes on its way to a pack.
 *
 * <p><b>It writes no patterns of its own.</b> Every decision about what looks like a secret belongs
 * to {@link SensitiveDataRedactor}, which the Guardian already owns and already tests. A second
 * redactor here would be a second answer to the same question: the two would drift, one of them
 * would be the weaker, and nobody would know which text had gone through which. This class exists
 * to say <em>when</em> redaction happens and <em>what</em> it covers — not how.
 *
 * <p><b>When: before everything.</b> Before the item is paired with its admission, before the
 * budget measures it, before the pack digest is taken, and long before a row is written. The budget
 * therefore measures the redacted text, which is the text that would actually leave the platform —
 * measuring the raw content would produce a ceiling enforced against something nobody will ever
 * see. Nothing downstream keeps the pre-redaction value: there is no field for it on the entity and
 * no column for it in the table, so "redact it later" is not a state this pipeline can be in.
 *
 * <p><b>It is also the only place a {@link RedactedContextItem} is minted on the materialisation
 * path.</b> Nothing downstream accepts a bare {@link ContextItem} any more: {@code
 * AdmittedContextItem} takes the redacted form, so a pack built by hand out of raw items does not
 * compile. That turns "redaction runs first" from a property of this pipeline's ordering into a
 * property of the type system — with one honest gap, which is that this class cannot prove to the
 * domain that the text it hands over really went through the redactor above. It marks the boundary;
 * it does not certify the bytes. The architecture test is what keeps the boundary single: no other
 * production class in {@code ..context..} may depend on {@link RedactedContextItem} at all, so no
 * other class can reach {@link RedactedContextItem#producedByRedaction(ContextItem)} by a call, a
 * method reference, or any other route. Stated as a dependency and not as a call because the
 * call-shaped version of this rule was walked through with a method reference, and a rule that
 * enumerates access kinds is open to whichever kind is thought of next.
 *
 * <p><b>What: every piece of free text a pack stores.</b> That is an item's content, an item's
 * label, and the pack's own task reference. Content is the obvious one. The label is redacted
 * because it is stored, displayed, and written by the same collectors from the same records — a
 * title reading {@code TOKEN=...} would leak exactly as effectively as a body would, and more
 * quietly, because a label is short enough that nobody reads it as payload. The task reference is
 * redacted because callers compose it from a task title the user wrote, so it is user text on the
 * same footing as the rest — see {@link #redactTaskReference(String)}.
 *
 * <p><b>What not: the item id and its provenance.</b> An id is a handle a collector constructs from
 * a record's primary key, and provenance is a type, an id and an instant. Neither carries free text
 * a secret could hide in, and redacting a handle would break the traceability the whole engine
 * rests on for no gain. If a collector ever starts folding user-supplied text into an id, that is a
 * defect in the collector, not a reason to widen this.
 *
 * <p>That list is exhaustive on purpose, and it is the list a reader is entitled to trust. A field
 * this class does not name is a field that reaches the database in the clear; the task reference
 * was exactly that until it was added above, and it was missed precisely because the exclusion list
 * read as complete when it was not. Anything added to a pack from here on belongs in one list or
 * the other, explicitly.
 *
 * <p>Redaction is not a licence. It removes values that match known secret shapes; it does not make
 * an item safe in general, and it is not a substitute for the policy that decided the item could be
 * here at all. And it is emphatically not the vault: this module cannot reach secret material by
 * any route, so nothing redacted here was ever a resolved credential — it is text from an official
 * record that happened to have a credential written into it.
 */
public final class ContextRedaction {

  private ContextRedaction() {}

  /**
   * The item with its content and label redacted.
   *
   * <p>Carries the same item through unchanged when nothing matched, so an unnecessary copy is not
   * made and an equality check upstream still holds — the wrapper is added either way, because a
   * caller must not be able to tell a clean item from a scrubbed one by its type.
   *
   * @throws IllegalStateException if redaction leaves nothing behind. Not expected: every pattern
   *     in {@link SensitiveDataRedactor} replaces a value with a marker rather than deleting it, so
   *     a non-blank input stays non-blank. It is checked anyway because the alternative failure is
   *     an {@link IllegalArgumentException} thrown from deep inside {@code ContextItem} with no
   *     indication that redaction caused it — and the temptation then would be to drop the item
   *     quietly, which is how a redactor change would start silently shortening packs.
   */
  /**
   * The task reference with secret-shaped values removed.
   *
   * <p>Separate from {@link #redact(ContextItem)} because a task reference is not an item: it is a
   * field of the pack, it is stored in its own column, and nothing about it goes through
   * provenance. It is redacted for the same reason a label is — a caller composes it from a task
   * title the user wrote, so it carries whatever the user put there.
   *
   * <p>It must run in the compiler, before the digest is taken, so the canonical payload and the
   * stored column hold the same string. Redacting at the persistence boundary instead would leave
   * a digest computed over text that no longer exists anywhere.
   *
   * @throws IllegalStateException if redaction leaves nothing behind, for the reason given on
   *     {@link #redact(ContextItem)}: a pack refuses a blank task reference, and the exception it
   *     would throw would not say that redaction was the cause
   */
  public static String redactTaskReference(String taskReference) {
    if (taskReference == null) {
      // Left for ContextPack to reject, which it does with a message about what a task reference
      // is for. Inventing a different error here would only hide that one.
      return null;
    }
    String redacted = SensitiveDataRedactor.redact(taskReference);
    if (!taskReference.isBlank() && redacted.isBlank()) {
      throw new IllegalStateException(
          "Redaction emptied the task reference, so the pack has nothing left to say it is for");
    }
    return redacted;
  }

  public static RedactedContextItem redact(ContextItem item) {
    if (item == null) {
      throw new IllegalArgumentException("There is nothing to redact");
    }
    String redactedContent = SensitiveDataRedactor.redact(item.content());
    String redactedLabel = SensitiveDataRedactor.redact(item.label());

    if (redactedContent.equals(item.content()) && redactedLabel.equals(item.label())) {
      // Still wrapped, and deliberately so. "Nothing matched" is a result of redaction, not a
      // reason to skip the boundary: the caller must be unable to tell the two cases apart, or the
      // clean path would be the one with no marker on it.
      return RedactedContextItem.producedByRedaction(item);
    }
    if (redactedContent.isBlank() || redactedLabel.isBlank()) {
      throw new IllegalStateException(
          "Redaction emptied item "
              + item.id()
              + ": the redactor now removes text rather than replacing it with a marker, which"
              + " would shorten packs silently if this item were dropped instead");
    }
    return RedactedContextItem.producedByRedaction(
        new ContextItem(
            item.id(), item.kind(), redactedLabel, redactedContent, item.provenance()));
  }
}
