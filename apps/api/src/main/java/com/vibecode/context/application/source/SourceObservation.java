package com.vibecode.context.application.source;

import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Picks the instant a computed read of several records should be dated at.
 *
 * <p>Some sources are not rows. The computed project state and the security posture are derived on
 * every request, and the obvious timestamp for them — "now" — is the wrong one:
 * {@code ContextProvenance.recordedAt} is defined as when the underlying state was observed, so that
 * two packs built minutes apart from unchanged records carry the same instant and a stale item reads
 * as stale. Dating a derived item at the moment of assembly would make every item look fresh and
 * would also make collection non-deterministic.
 *
 * <p>So a derived item is dated at the most recent change among the records it was computed from.
 * That instant moves only when one of those records moves, which is exactly the property
 * {@code recordedAt} promises.
 */
final class SourceObservation {

  private SourceObservation() {}

  /**
   * The latest of the given instants, ignoring nulls, falling back when every candidate is null.
   *
   * @param fallback used when no contributing record carries a timestamp; must not be null
   */
  static Instant latestOf(Instant fallback, Instant... candidates) {
    Objects.requireNonNull(fallback, "A derived observation needs a fallback instant");
    return Stream.of(candidates)
        .filter(Objects::nonNull)
        .max(Instant::compareTo)
        .filter(latest -> latest.isAfter(fallback))
        .orElse(fallback);
  }
}
