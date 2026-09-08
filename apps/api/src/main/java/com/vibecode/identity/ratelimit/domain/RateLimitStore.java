package com.vibecode.identity.ratelimit.domain;

import java.time.Instant;

/**
 * Where bucket state lives.
 *
 * <p>An interface because the current implementation is in-process and therefore per-instance: two
 * application instances would each allow the full quota. Replacing it with a shared store is the
 * whole change needed to make the limiter global — see {@code MULTI_INSTANCE_RATE_LIMIT_STORE}.
 */
public interface RateLimitStore {

  /**
   * Takes one token if the bucket has one. Must be atomic per key: two concurrent callers on the
   * last token cannot both succeed.
   */
  boolean tryConsume(RateLimitKey key, RateLimitPolicy policy, Instant now);

  /** Puts the bucket back to full. Used after a successful login, per policy. */
  void reset(RateLimitKey key);

  /** How many buckets are currently held. Bounded by construction. */
  int size();
}
