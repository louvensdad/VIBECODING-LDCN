package com.vibecode.identity.domain;

/**
 * Whether an account may be used.
 *
 * <p>Only {@link #ACTIVE} can authenticate. The other two are deliberately distinct: DISABLED is a
 * deliberate administrative decision, LOCKED is a protective reaction. Collapsing them would lose
 * the reason an account stopped working.
 */
public enum UserStatus {
  ACTIVE,
  /** Turned off on purpose. */
  DISABLED,
  /** Locked for protection. */
  LOCKED;

  public boolean canAuthenticate() {
    return this == ACTIVE;
  }
}
