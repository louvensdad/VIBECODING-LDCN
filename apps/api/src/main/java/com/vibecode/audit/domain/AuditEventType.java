package com.vibecode.audit.domain;

/** Canonical event types for the tamper-evident, append-only security audit trail. */
public enum AuditEventType {
  SECURITY_FINDING_CREATED,
  SECURITY_FINDING_ACKNOWLEDGED,
  SECURITY_FINDING_RESOLVED,
  SECURITY_RISK_ACCEPTED,
  SECURITY_GATE_BLOCKED,
  PROMPT_BLOCKED,
  CROSS_USER_ACCESS_DENIED,
  LOGIN_SUCCESS,
  LOGIN_FAILURE,
  LOGOUT,
  /** An authentication attempt refused by the rate limiter, before any credential was checked. */
  AUTH_RATE_LIMITED,
  REGISTRATION_RATE_LIMITED,

  /* Provider connections and their credentials. None of these events carries secret material:
   * they record that something happened to a credential, never anything about its value. */
  PROVIDER_ACCOUNT_CREATED,
  PROVIDER_ACCOUNT_DISABLED,
  PROVIDER_CREDENTIAL_STORED,
  PROVIDER_CREDENTIAL_ROTATED,
  PROVIDER_CREDENTIAL_REMOVED,

  /**
   * The vault could not decrypt something it was asked for.
   *
   * <p>Worth an audit row on its own: it means a wrong key, a tampered row or a corrupted backup,
   * and any of the three is a security event rather than an ordinary error.
   */
  VAULT_DECRYPTION_FAILED
}

