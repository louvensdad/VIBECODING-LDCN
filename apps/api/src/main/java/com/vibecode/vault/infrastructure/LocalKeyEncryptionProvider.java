package com.vibecode.vault.infrastructure;

import com.vibecode.vault.domain.KeyEncryptionProvider;
import com.vibecode.vault.domain.VaultCryptographyException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wraps data keys with a master key held in the environment.
 *
 * <p>For development. The master key is only as protected as the environment holding it, which is
 * why using this outside development requires an explicit, loudly named opt-in.
 *
 * <p>The wrapping itself is AES-GCM, not AES-KW: the same authenticated construction as the payload
 * encryption, so a tampered wrapped key fails to unwrap rather than yielding a wrong key that then
 * produces garbage.
 */
public class LocalKeyEncryptionProvider implements KeyEncryptionProvider {

  public static final String PROVIDER_ID = "LOCAL";

  private static final int MASTER_KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  /** Distinguishes key wrapping from payload encryption in the authenticated data. */
  private static final byte[] WRAP_AAD = "vibecode:vault:kek:v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private final SecretKey masterKey;
  private final String keyVersion;
  private final SecureRandom random = new SecureRandom();

  public LocalKeyEncryptionProvider(String base64MasterKey, String keyVersion) {
    this.masterKey = parseMasterKey(base64MasterKey);
    this.keyVersion = keyVersion;
  }

  /**
   * Reads and checks the master key.
   *
   * <p>Every failure message describes the problem without ever echoing the value — a
   * misconfiguration should not put the key, or a fragment of it, into a startup log.
   */
  private static SecretKey parseMasterKey(String base64MasterKey) {
    if (base64MasterKey == null || base64MasterKey.isBlank()) {
      throw new VaultStartupException(
          "The vault is enabled but no master key was provided. Set VIBECODE_VAULT_MASTER_KEY to "
              + "the base64 of 32 random bytes. The application will not start without it, and it "
              + "will not invent one: a generated key would make every stored secret unreadable.");
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(base64MasterKey.trim());
    } catch (IllegalArgumentException invalid) {
      throw new VaultStartupException(
          "The vault master key is not valid base64. Expected the base64 of 32 random bytes.");
    }
    if (decoded.length != MASTER_KEY_BYTES) {
      int actual = decoded.length;
      Arrays.fill(decoded, (byte) 0);
      throw new VaultStartupException(
          "The vault master key must decode to exactly "
              + MASTER_KEY_BYTES
              + " bytes, but it decoded to "
              + actual
              + ".");
    }
    SecretKey key = new SecretKeySpec(decoded, "AES");
    Arrays.fill(decoded, (byte) 0);
    return key;
  }

  @Override
  public String providerId() {
    return PROVIDER_ID;
  }

  @Override
  public String keyVersion() {
    return keyVersion;
  }

  @Override
  public byte[] wrapKey(SecretKey dataEncryptionKey) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(WRAP_AAD);
      byte[] wrapped = cipher.doFinal(dataEncryptionKey.getEncoded());

      // The nonce travels with the wrapped key; it is not secret, only single-use.
      byte[] result = new byte[NONCE_BYTES + wrapped.length];
      System.arraycopy(nonce, 0, result, 0, NONCE_BYTES);
      System.arraycopy(wrapped, 0, result, NONCE_BYTES, wrapped.length);
      return result;
    } catch (Exception failure) {
      throw new VaultCryptographyException("Could not wrap the data encryption key", failure);
    }
  }

  @Override
  public SecretKey unwrapKey(byte[] wrappedKey, String requestedKeyVersion) {
    if (wrappedKey == null || wrappedKey.length <= NONCE_BYTES) {
      throw new VaultCryptographyException("The wrapped data encryption key is malformed");
    }
    if (!keyVersion.equals(requestedKeyVersion)) {
      // A version this provider does not hold cannot be unwrapped. Saying so is safe: the version
      // label is not secret, and a silent wrong-key attempt would only fail with less information.
      throw new VaultCryptographyException(
          "This provider holds key version " + keyVersion + ", not " + requestedKeyVersion);
    }
    try {
      byte[] nonce = Arrays.copyOfRange(wrappedKey, 0, NONCE_BYTES);
      byte[] payload = Arrays.copyOfRange(wrappedKey, NONCE_BYTES, wrappedKey.length);

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(WRAP_AAD);
      byte[] keyBytes = cipher.doFinal(payload);

      SecretKey dataKey = new SecretKeySpec(keyBytes, "AES");
      Arrays.fill(keyBytes, (byte) 0);
      return dataKey;
    } catch (Exception failure) {
      // Uniform message: a wrong master key and a tampered wrapped key are indistinguishable here
      // on purpose.
      throw new VaultCryptographyException("Could not unwrap the data encryption key", failure);
    }
  }

  /** Refuses startup rather than continuing with a vault that cannot be trusted. */
  public static class VaultStartupException extends RuntimeException {

    public VaultStartupException(String message) {
      super(message);
    }
  }
}
