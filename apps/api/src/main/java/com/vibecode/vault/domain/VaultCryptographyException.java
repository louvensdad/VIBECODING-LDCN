package com.vibecode.vault.domain;

/**
 * Any failure to encrypt or decrypt.
 *
 * <p>Deliberately uniform: a caller learns that the operation failed, never why. Distinguishing a
 * wrong key from a tampered ciphertext from a bad tag would hand an attacker an oracle, and the
 * underlying {@code AEADBadTagException} never reaches a client.
 */
public class VaultCryptographyException extends RuntimeException {

  public VaultCryptographyException(String message) {
    super(message);
  }

  public VaultCryptographyException(String message, Throwable cause) {
    // The cause is kept for server-side diagnosis only; the handler never serialises it.
    super(message, cause);
  }
}
