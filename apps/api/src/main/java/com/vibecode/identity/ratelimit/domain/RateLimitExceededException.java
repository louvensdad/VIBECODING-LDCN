package com.vibecode.identity.ratelimit.domain;

/**
 * Raised when a bucket is empty. Mapped to HTTP 429.
 *
 * <p>The decision is carried for the audit trail; the message that reaches the caller says nothing
 * about which limit was hit or how much is left.
 */
public class RateLimitExceededException extends RuntimeException {

  private final transient RateLimitDecision decision;

  public RateLimitExceededException(RateLimitDecision decision) {
    super("Rate limit exceeded: " + decision.policyId());
    this.decision = decision;
  }

  public RateLimitDecision decision() {
    return decision;
  }
}
