package com.vibecode.context.domain;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;

/**
 * One unit of context, with the record it came from attached.
 *
 * <p>Provenance is a constructor argument and is rejected when absent, so an item that nobody can
 * trace back to official state cannot exist even briefly. There is no mutator and no builder that
 * would allow "construct now, justify later".
 *
 * <p><b>Size is measured over {@link #content()} only.</b> That is the text a renderer would emit;
 * {@link #id()} and {@link #label()} are handles for the Context Inspector, not payload. The label
 * is therefore uncounted by {@link ContextBudget} on purpose — while still being redacted, still
 * capped at {@link #MAX_LABEL_LENGTH}, and still part of the canonical payload and the digest. See
 * {@link ContextBudget} for what would have to change if a label ever became something a provider
 * is sent. Whatever
 * separators or headings a later assembly step puts <em>between</em> items is that step's cost to
 * account for — this class does not know about them and does not pretend to.
 *
 * <p>Items are ordered by {@link #CANONICAL_ORDER} and by nothing else. This type deliberately does
 * not implement {@link Comparable}: the comparator's last key is the item id, so two items sharing
 * an id but differing in content compare equal while {@code equals} says they are not. A pack
 * rejects duplicate ids and so never meets that case, but a {@code Comparable} item would invite a
 * sorted set or map somewhere else in the pipeline to discard one of the two silently. One ordering
 * authority, reachable only where it is meant to be used.
 *
 * <p>Nothing here may ever hold secret material. Not a credential, not a token, not a vault handle:
 * an item is text drawn from official records, and a record that would carry a secret is not a
 * source this engine reads.
 *
 * @param id identifier of this item within its pack, unique there and stable across rebuilds
 * @param kind what the item is
 * @param label a short human handle, for the inspector
 * @param content the text itself
 * @param provenance where it came from; mandatory
 */
public record ContextItem(
    String id, ContextKind kind, String label, String content, ContextProvenance provenance) {

  /**
   * The longest a label may be, in the same <b>UTF-16 code units</b> {@link #characterCount()}
   * counts. 500 because that is the width of {@code context_pack_items.label} in V9, and this
   * constant exists so an over-long label is refused here rather than by an INSERT.
   *
   * <p><b>Checking here is what makes the failure early, and redaction is why it has to be.</b>
   * {@code ContextRedaction} builds a fresh item from the redacted strings, so this constructor
   * runs again on the redacted label — and redaction can <em>lengthen</em> it: a value replaced by
   * {@code [REDACTED]} is longer than a short secret was. A 498-character label ending in a
   * secret-shaped assignment measured 507 after redaction and failed at the INSERT, after policy,
   * budgeting and selection had all done their work. Now it fails where the value is made.
   *
   * <p><b>What this does not do.</b> It does not truncate, and nothing here shortens a label to
   * fit: a silently trimmed label is a handle that no longer matches the record it names, and the
   * digest would be taken over the trimmed form. It also cannot say <em>which</em> step produced
   * the over-long value — the exception is thrown by construction, so a redaction-lengthened label
   * surfaces as a construction failure inside the redactor rather than as a message about
   * redaction. Naming the likely cause in the message is as close as the domain can get without
   * knowing about a pipeline it must not see.
   *
   * <p>Counting code units and not code points is deliberate and matches {@link
   * ContextBudget#maxCharacters()}: "😀" is 2 here as it is there. It is also the safe direction
   * against the column — PostgreSQL counts {@code VARCHAR(500)} in code points, which is never
   * more than the code-unit count, so a label this type accepts always fits. H2 counts code units,
   * so under test the two agree exactly.
   */
  public static final int MAX_LABEL_LENGTH = 500;

  /**
   * The canonical order of items — total within a pack, where item ids are unique.
   *
   * <p>Source first, then kind, then the source record's identifier, then the item id. The last key
   * is what makes the order total <em>in a pack</em>: ids are unique there, so no two items compare
   * equal and no outcome is left to the order the caller happened to hand items in. Over an
   * arbitrary collection that has not been through {@link ContextPack}, two items sharing an id
   * still compare equal — the uniqueness the totality rests on is the pack's invariant, not this
   * comparator's.
   *
   * <p>Nothing here consults a hash, a clock or insertion order: all three vary between runs that
   * must produce identical packs.
   *
   * <p><b>This is not a drop policy, and must not be used as one.</b> It exists to make a pack
   * reproducible — same inputs, same bytes, every time. Which item to leave out when a budget binds
   * is a different question with different answers, and the step that selects context owes an
   * explicit policy under its own name. Reusing this comparator for that would tie the two together:
   * reordering an enum to make a pack read better would silently change which standing rule falls
   * out of a truncated one, and nobody would connect the two edits.
   */
  public static final Comparator<ContextItem> CANONICAL_ORDER =
      Comparator.comparingInt((ContextItem item) -> item.provenance().sourceType().orderingRank())
          .thenComparingInt(item -> item.kind().orderingRank())
          .thenComparing(item -> item.provenance().sourceId())
          .thenComparing(ContextItem::id);

  public ContextItem {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("A context item must have an id");
    }
    if (kind == null) {
      throw new IllegalArgumentException("A context item must declare its kind");
    }
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("A context item must have a label");
    }
    if (label.length() > MAX_LABEL_LENGTH) {
      throw new IllegalArgumentException(
          "A context item label may not exceed "
              + MAX_LABEL_LENGTH
              + " characters (UTF-16 code units); item "
              + id
              + " has "
              + label.length()
              + ". A label arriving over the cap after redaction is the expected cause: replacing"
              + " a short secret with a marker lengthens the string.");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("A context item must carry content");
    }
    if (provenance == null) {
      throw new IllegalArgumentException(
          "A context item cannot exist without provenance: every item must name the record it came from");
    }
  }

  /**
   * The length of {@code content} in <b>UTF-16 code units</b>, which is what {@link String#length()}
   * counts and what {@link ContextBudget#maxCharacters()} is measured against.
   *
   * <p>It is exact, but it is not a count of user-visible characters: "😀" counts 2, and every
   * character outside the Basic Multilingual Plane counts 2. Anything downstream that trims content
   * must trim in the same unit — a truncator written against {@code codePointCount} would compare
   * against a budget measured in a different currency, and one written against {@code substring}
   * must not cut between the halves of a surrogate pair.
   */
  public long characterCount() {
    return content.length();
  }

  /** Exact for UTF-8, which is what every transport in this system uses. */
  public long byteCount() {
    return content.getBytes(StandardCharsets.UTF_8).length;
  }
}
