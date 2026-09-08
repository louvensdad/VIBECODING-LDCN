package com.vibecode.identity.ratelimit.domain;

import java.time.Duration;

/**
 * How much is allowed, and over what period. Policy only — it holds no counters.
 *
 * @param policyId short stable name, safe to put in an audit record or a metric tag
 * @param capacity how many attempts the bucket holds when full
 * @param window how long a fully drained bucket takes to refill
 */
public record RateLimitPolicy(
    RateLimitScope scope, String policyId, int capacity, Duration window) {

  public RateLimitPolicy {
    if (capacity < 1) {
      throw new IllegalArgumentException("Rate limit capacity must be at least 1: " + policyId);
    }
    if (window == null || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("Rate limit window must be positive: " + policyId);
    }
  }

  /** Tokens restored per millisecond. Refill is continuous, not a cliff at the window edge. */
  public double refillPerMilli() {
    return (double) capacity / window.toMillis();
  }

  /** How long an idle bucket is kept before it can be discarded. */
  public Duration idleRetention() {
    return window.multipliedBy(2);
  }
}
