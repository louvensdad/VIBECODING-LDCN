package com.vibecode.identity.ratelimit.domain;

/**
 * The independent dimensions abuse is measured on.
 *
 * <p>Origin and identifier are separate buckets on purpose. A single key of {@code ip + email}
 * would give an attacker a fresh allowance for every address they invent, so one machine could
 * work through thousands of accounts without ever filling a bucket. Measuring the two
 * independently means volume from one place is capped whatever names it uses, and pressure on one
 * account is capped wherever it comes from.
 */
public enum RateLimitScope {
  /** Volume from one client, whatever identifiers it tries. */
  LOGIN_ORIGIN,
  /** Pressure on one account, from wherever it comes. */
  LOGIN_ACCOUNT,
  REGISTER_ORIGIN,
  REGISTER_IDENTIFIER;

  public boolean isOrigin() {
    return this == LOGIN_ORIGIN || this == REGISTER_ORIGIN;
  }
}
