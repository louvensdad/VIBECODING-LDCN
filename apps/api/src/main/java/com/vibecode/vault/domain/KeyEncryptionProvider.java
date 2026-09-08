package com.vibecode.vault.domain;

import javax.crypto.SecretKey;

/**
 * Wraps and unwraps the per-secret data keys.
 *
 * <p>This is the seam that keeps the vault independent of where the master key lives. Envelope
 * encryption exists precisely so this can become AWS KMS, Cloud KMS or an HSM without any stored
 * ciphertext being re-encrypted — only the wrapped data keys would be re-wrapped.
 */
public interface KeyEncryptionProvider {

  /** Stable identifier persisted with every version, so a row says which provider wrapped it. */
  String providerId();

  /**
   * Which key generation was used. Persisted alongside the wrapped key so a future master-key
   * rotation can find the versions that still need re-wrapping.
   */
  String keyVersion();

  byte[] wrapKey(SecretKey dataEncryptionKey);

  /**
   * @throws VaultCryptographyException when the wrapped key cannot be recovered — a wrong master
   *     key, a tampered row, or a key version that no longer exists
   */
  SecretKey unwrapKey(byte[] wrappedKey, String keyVersion);
}
