package com.vibecode.identity.ratelimit.domain;

import java.time.Instant;

/**
 * The internal outcome of a check.
 *
 * <p>Carries which policy refused, for the audit trail and metrics. None of this reaches an
 * unauthenticated caller: telling a client which bucket tripped, or how much is left, is telling
 * automated abuse exactly how to pace itself.
 */
public record RateLimitDecision(
    boolean allowed, RateLimitScope scope, String policyId, Instant observedAt) {

  public static RateLimitDecision allowed(RateLimitPolicy policy, Instant now) {
    return new RateLimitDecision(true, policy.scope(), policy.policyId(), now);
  }

  public static RateLimitDecision denied(RateLimitPolicy policy, Instant now) {
    return new RateLimitDecision(false, policy.scope(), policy.policyId(), now);
  }

  public boolean denied() {
    return !allowed;
  }
}
