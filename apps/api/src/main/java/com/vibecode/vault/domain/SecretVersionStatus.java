package com.vibecode.vault.domain;

/**
 * Lifecycle of one encrypted version.
 *
 * <p>Exactly one version of a secret is ACTIVE at a time. The previous one is RETIRED rather than
 * deleted, so a rotation that fails half way never leaves a secret with no readable version.
 */
public enum SecretVersionStatus {
  ACTIVE,
  RETIRED,
  /** Key material overwritten; the ciphertext can no longer be decrypted. */
  DESTROYED
}
