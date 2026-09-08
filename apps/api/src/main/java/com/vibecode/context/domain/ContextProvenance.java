package com.vibecode.context.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Where one context item came from, and when that was true.
 *
 * <p>This type exists so that provenance is structural rather than a habit. Every {@link
 * ContextItem} holds one, the item's constructor rejects a missing one, and there is no setter that
 * would let a caller build an item first and justify it afterwards. If an item is in a pack, someone
 * can point at the record that produced it.
 *
 * <p>{@code recordedAt} is the moment the underlying state was read, not the moment the pack was
 * assembled. Two packs built minutes apart from the same unchanged record carry the same
 * {@code recordedAt}, which is what makes a stale item visible as stale.
 *
 * @param source the record this item was drawn from
 * @param projectId the project that owns that record; carried explicitly so an item can never be
 *     read as belonging to a project it was not drawn from
 * @param recordedAt when the source state was observed
 */
public record ContextProvenance(ContextSource source, UUID projectId, Instant recordedAt) {

  public ContextProvenance {
    if (source == null) {
      throw new IllegalArgumentException("Provenance must name its source");
    }
    if (projectId == null) {
      throw new IllegalArgumentException("Provenance must name the project the source belongs to");
    }
    if (recordedAt == null) {
      throw new IllegalArgumentException("Provenance must record when the source was observed");
    }
  }

  public ContextSourceType sourceType() {
    return source.type();
  }

  public String sourceId() {
    return source.sourceId();
  }

  public Optional<Integer> sourceVersion() {
    return source.sourceVersion();
  }
}
