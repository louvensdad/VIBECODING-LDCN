package com.vibecode.provider.domain;

/**
 * What the platform actually knows about a connection.
 *
 * <p>There is no CONNECTED or VALID here, and that is deliberate. No request has ever been made to
 * the provider, so the platform has no evidence the credential works. Claiming otherwise would be
 * the same failure the Output Analyzer exists to prevent: reporting a state nothing verified.
 */
public enum ProviderAccountStatus {
  /** The connection exists; no credential has been stored yet. */
  PENDING_CREDENTIAL,
  /** A credential is stored and encrypted. Whether it works is unknown. */
  CREDENTIAL_STORED_UNVERIFIED,
  /** Switched off by the user. */
  DISABLED;

  public boolean isUsable() {
    return this == CREDENTIAL_STORED_UNVERIFIED;
  }
}
