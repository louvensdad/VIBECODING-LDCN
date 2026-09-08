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
  REGISTRATION_RATE_LIMITED
}

