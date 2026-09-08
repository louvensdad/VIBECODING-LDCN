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
 * {@link #id()} and {@link #label()} are handles for the Context Inspector, not payload. Whatever
 * separators or headings a later assembly step puts <em>between</em> items is that step's cost to
 * account for — this class does not know about them and does not pretend to.
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
    String id, ContextKind kind, String label, String content, ContextProvenance provenance)
    implements Comparable<ContextItem> {

  /**
   * The canonical total order of items.
   *
   * <p>Source first, then kind, then the source record's identifier, then the item id. The last key
   * is what makes the order <em>total</em>: item ids are unique within a pack, so no two items can
   * compare equal and no comparison outcome is left to the order the caller happened to hand items
   * in. Nothing here consults a hash, a clock or insertion order — all three vary between runs that
   * should produce identical packs.
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
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("A context item must carry content");
    }
    if (provenance == null) {
      throw new IllegalArgumentException(
          "A context item cannot exist without provenance: every item must name the record it came from");
    }
  }

  /** Exact, by definition — {@code content} is already in memory. */
  public long characterCount() {
    return content.length();
  }

  /** Exact for UTF-8, which is what every transport in this system uses. */
  public long byteCount() {
    return content.getBytes(StandardCharsets.UTF_8).length;
  }

  @Override
  public int compareTo(ContextItem other) {
    return CANONICAL_ORDER.compare(this, other);
  }
}
