package com.vibecode.context.domain;

import java.util.Optional;

/**
 * The addressable origin of a context item: a type plus the identifier of the one record within it.
 *
 * <p>The identifier matters as much as the type. "This came from a brain decision" is not auditable;
 * "this came from brain decision {@code 7f3c…}, version 4" can be looked up, disagreed with, and
 * superseded. That is the difference between provenance and a label.
 *
 * <p>{@code version} is optional because only some underlying records are versioned. Where a version
 * exists it must be supplied — an unversioned reference to a versioned record cannot be resolved
 * back to the exact text that was used.
 *
 * @param type which official record produced the item
 * @param sourceId identifier of the specific record, unique within its type
 * @param version the record's revision, or {@code null} where the record has none
 */
public record ContextSource(ContextSourceType type, String sourceId, Integer version) {

  public ContextSource {
    if (type == null) {
      throw new IllegalArgumentException("A context source must name its type");
    }
    if (sourceId == null || sourceId.isBlank()) {
      throw new IllegalArgumentException("A context source must identify the record it came from");
    }
    if (version != null && version < 1) {
      throw new IllegalArgumentException("A source version starts at 1, got: " + version);
    }
  }

  /** An unversioned source, for records that carry no revision. */
  public static ContextSource of(ContextSourceType type, String sourceId) {
    return new ContextSource(type, sourceId, null);
  }

  /** A versioned source, for records that do. */
  public static ContextSource versioned(ContextSourceType type, String sourceId, int version) {
    return new ContextSource(type, sourceId, version);
  }

  public Optional<Integer> sourceVersion() {
    return Optional.ofNullable(version);
  }
}
