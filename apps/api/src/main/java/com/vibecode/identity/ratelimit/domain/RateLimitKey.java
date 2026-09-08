package com.vibecode.identity.ratelimit.domain;

/**
 * What a bucket is counted against.
 *
 * <p>{@code fingerprint} is never a raw email or a raw address — see {@code IdentifierFingerprint}
 * and {@code ClientOriginResolver}. Keeping the raw value out of the key means a heap dump, a log
 * line or a debugger session cannot turn the limiter into a list of who has been trying to sign in.
 */
public record RateLimitKey(RateLimitScope scope, String fingerprint) {

  public RateLimitKey {
    if (fingerprint == null || fingerprint.isBlank()) {
      throw new IllegalArgumentException("A rate limit key needs a fingerprint");
    }
  }
}
