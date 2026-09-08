package com.vibecode.context.application.redaction;

import com.vibecode.context.domain.ContextItem;
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
 * <p><b>What: content and label.</b> Content is the obvious one. The label is redacted too because
 * it is stored, displayed, and written by the same collectors from the same records — a title
 * reading {@code TOKEN=...} would leak exactly as effectively as a body would, and more quietly,
 * because a label is short enough that nobody reads it as payload.
 *
 * <p><b>What not: the item id and its provenance.</b> An id is a handle a collector constructs from
 * a record's primary key, and provenance is a type, an id and an instant. None of them carries free
 * text a secret could hide in, and redacting a handle would break the traceability the whole engine
 * rests on for no gain. If a collector ever starts folding user-supplied text into an id, that is a
 * defect in the collector, not a reason to widen this.
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
   * <p>Returns the same item unchanged when nothing matched, so an unnecessary copy is not made and
   * an equality check upstream still holds.
   *
   * @throws IllegalStateException if redaction leaves nothing behind. Not expected: every pattern
   *     in {@link SensitiveDataRedactor} replaces a value with a marker rather than deleting it, so
   *     a non-blank input stays non-blank. It is checked anyway because the alternative failure is
   *     an {@link IllegalArgumentException} thrown from deep inside {@code ContextItem} with no
   *     indication that redaction caused it — and the temptation then would be to drop the item
   *     quietly, which is how a redactor change would start silently shortening packs.
   */
  public static ContextItem redact(ContextItem item) {
    if (item == null) {
      throw new IllegalArgumentException("There is nothing to redact");
    }
    String redactedContent = SensitiveDataRedactor.redact(item.content());
    String redactedLabel = SensitiveDataRedactor.redact(item.label());

    if (redactedContent.equals(item.content()) && redactedLabel.equals(item.label())) {
      return item;
    }
    if (redactedContent.isBlank() || redactedLabel.isBlank()) {
      throw new IllegalStateException(
          "Redaction emptied item "
              + item.id()
              + ": the redactor now removes text rather than replacing it with a marker, which"
              + " would shorten packs silently if this item were dropped instead");
    }
    return new ContextItem(
        item.id(), item.kind(), redactedLabel, redactedContent, item.provenance());
  }
}
