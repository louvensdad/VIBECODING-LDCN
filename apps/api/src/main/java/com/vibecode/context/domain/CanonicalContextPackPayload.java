package com.vibecode.context.domain;

import java.util.List;

/**
 * The canonical, storage-free representation of a compiled pack — and the exact bytes the pack
 * digest is taken over.
 *
 * <p><b>Why this type exists at all.</b> "The same inputs produce the same pack" cannot honestly be
 * claimed of the persisted row: {@code packId} is a fresh UUID every time and {@code created_at} is
 * a clock reading, so two byte-identical compilations produce two different rows and always will.
 * What can be claimed, and is, is that the same logical inputs produce the same canonical payload
 * below, character for character. Everything that legitimately varies between two compilations of
 * unchanged state is excluded, and everything a reader would call part of the decision is included.
 *
 * <p><b>Covered</b>, in this order: the policy version; the project and the task the pack was
 * compiled for; the budget it was held to, all three dimensions; the number of items; and then, per
 * item in canonical order — item id, kind, label, redacted content, source type, source id, source
 * version, the admitting rule id, and that rule's explanation.
 *
 * <p>The rule id and the explanation are inside the digest, not beside it. A pack whose items were
 * admitted under different rules is a different pack even if the text came out the same: dropping
 * the rule id would let a policy change that silently swapped which rule admits an item leave no
 * trace at all, which is precisely the change a reader most needs to see. The budget is covered for
 * the same reason — it is an input to selection, so two packs that happen to hold the same items
 * under different ceilings did not answer the same question.
 *
 * <p><b>Deliberately excluded, and for three different reasons.</b> {@code packId} and the row's
 * {@code created_at} are storage identity and a clock reading. {@code assembledAt} moves on every
 * rebuild of unchanged state, so including it would defeat the only thing the digest is for.
 * {@code provenance.projectId} is already forced equal to the pack's own project for every item,
 * so it can add no distinguishing information.
 *
 * <p>{@code provenance.recordedAt} is excluded for none of those reasons, and it is worth being
 * exact about which. It does <em>not</em> move on every rebuild: every collector derives it from a
 * stored record's own timestamp, so two compilations of unchanged state carry the same instant and
 * it moves only when the underlying record does. It is left out because the digest answers "is
 * this the same context?" and not "was it read at the same moment" — an item whose text, source,
 * version and admitting rule are all identical is the same context whether it was read today or
 * last year. {@link ContextPack#contentFingerprint()} excludes it too, and the two agreeing is
 * deliberate. The cost, stated rather than discovered: two packs differing only in when the source
 * was observed share a digest.
 *
 * <p><b>The encoding is unambiguous by length prefix, not by separator.</b> Item content is text
 * this domain does not control, so any character chosen as a boundary can also occur inside a
 * field. Every field is written as its length in UTF-16 code units, a unit separator, the field,
 * and another unit separator; without the prefix a two-item pack could flatten to the same string
 * as a one-item pack whose content embeds the separator, and the two would share a digest.
 *
 * <p>The digest is an equality check over content, never a security control and never an identity.
 * It proves nothing about who compiled the pack. Identity is {@code packId}, a UUID, and stays so.
 */
public final class CanonicalContextPackPayload {

  /**
   * Separates fields. A readability aid only — the length prefix in front of every field is what
   * makes the encoding unambiguous.
   */
  private static final String FIELD_SEPARATOR = String.valueOf((char) 0x1F);

  /**
   * Written where a field has no value.
   *
   * <p>It is not an escape and it does not make "absent" distinguishable from a field whose value
   * is literally {@code "-"}. It happens not to matter, and only for a reason worth writing down:
   * the source version is the one optional field in the whole payload and it is an {@link Integer},
   * so it can never arrive as the text {@code "-"}. Give any free-text field an absent case and
   * this constant stops being safe — such a field needs a marker its own value cannot forge, or a
   * flag of its own.
   */
  private static final String ABSENT = "-";

  private final String canonical;

  private CanonicalContextPackPayload(String canonical) {
    this.canonical = canonical;
  }

  /**
   * Serialises a compiled pack's logical content.
   *
   * @param policyVersion the policy in force when the items were admitted
   * @param projectId the project the pack describes
   * @param taskReference the task it was compiled for
   * @param budget the ceiling selection was held to
   * @param items the selected items with their admissions, already in canonical order
   */
  public static CanonicalContextPackPayload of(
      ContextPolicyVersion policyVersion,
      java.util.UUID projectId,
      String taskReference,
      ContextBudget budget,
      List<AdmittedContextItem> items) {
    StringBuilder canonical = new StringBuilder();
    // A version tag on the encoding itself. If the field list below ever changes, every digest
    // ever computed changes with it — this makes that visible in the payload rather than leaving
    // two incompatible encodings sharing a name.
    appendField(canonical, "context-pack-canonical-v1");
    appendField(canonical, policyVersion.value());
    appendField(canonical, projectId.toString());
    appendField(canonical, taskReference);
    appendField(canonical, Integer.toString(budget.maxItems()));
    appendField(canonical, Long.toString(budget.maxCharacters()));
    appendField(canonical, Long.toString(budget.maxBytes()));
    appendField(canonical, Integer.toString(items.size()));

    for (AdmittedContextItem admitted : items) {
      ContextItem item = admitted.item();
      ContextProvenance provenance = item.provenance();
      appendField(canonical, item.id());
      appendField(canonical, item.kind().name());
      appendField(canonical, item.label());
      appendField(canonical, item.content());
      appendField(canonical, provenance.sourceType().name());
      appendField(canonical, provenance.sourceId());
      appendField(canonical, provenance.sourceVersion().map(String::valueOf).orElse(ABSENT));
      appendField(canonical, admitted.admission().policyRuleId());
      appendField(canonical, admitted.admission().explanation());
    }
    return new CanonicalContextPackPayload(canonical.toString());
  }

  /** The serialised form. Same logical inputs, same string, character for character. */
  public String value() {
    return canonical;
  }

  /** The SHA-256 of {@link #value()}, 64 lowercase hex characters. */
  public String digest() {
    return ContextDigest.sha256Hex(canonical);
  }

  /** Length first, so a field's own text cannot forge a boundary. */
  private static void appendField(StringBuilder canonical, String field) {
    canonical.append(field.length()).append(FIELD_SEPARATOR).append(field).append(FIELD_SEPARATOR);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof CanonicalContextPackPayload that && canonical.equals(that.canonical);
  }

  @Override
  public int hashCode() {
    return canonical.hashCode();
  }

  /**
   * The digest, never the payload. The payload is a verbatim copy of every item's content, and a
   * type whose {@code toString} printed context into a log would undo the redaction step that runs
   * before it.
   */
  @Override
  public String toString() {
    return "CanonicalContextPackPayload[digest=" + digest() + "]";
  }
}
