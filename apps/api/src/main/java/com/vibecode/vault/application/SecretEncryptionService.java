package com.vibecode.vault.application;

import com.vibecode.vault.domain.KeyEncryptionProvider;
import com.vibecode.vault.domain.SecretMaterial;
import com.vibecode.vault.domain.SecretPurpose;
import com.vibecode.vault.domain.VaultCryptographyException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Service;

/**
 * Encrypts and decrypts secret material. The only place in the application that runs a cipher.
 *
 * <p>Envelope encryption: a fresh data key per version encrypts the secret, and the key encryption
 * provider wraps that data key. Two things follow. A master key rotation only has to re-wrap short
 * data keys rather than decrypt and re-encrypt every stored secret, and moving to a real KMS never
 * touches stored ciphertext.
 *
 * <p>AES-GCM with a random 96-bit nonce per encryption and a 128-bit tag. Authenticated on purpose:
 * a modified ciphertext must fail loudly rather than decrypt to something.
 */
@Service
public class SecretEncryptionService {

  /** Bumped when the ciphertext layout or the AAD format changes, so old rows stay readable. */
  public static final short FORMAT_VERSION = 1;

  public static final String ALGORITHM = "AES-256-GCM";

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int DATA_KEY_BITS = 256;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final KeyEncryptionProvider keyProvider;
  private final SecureRandom random = new SecureRandom();

  public SecretEncryptionService(KeyEncryptionProvider keyProvider) {
    this.keyProvider = keyProvider;
  }

  /** Everything needed to store one encrypted version. Carries no plaintext and no data key. */
  public record EncryptedSecret(
      byte[] ciphertext,
      byte[] nonce,
      byte[] wrappedDataEncryptionKey,
      String algorithm,
      short formatVersion,
      String keyProviderId,
      String keyVersion) {}

  /** The non-secret context a ciphertext is bound to. */
  public record SecretContext(
      UUID secretId, UUID ownerUserId, SecretPurpose purpose, int version) {}

  /**
   * Builds the additional authenticated data.
   *
   * <p>Binding the ciphertext to its identity, owner, purpose and version means a row copied into
   * another secret — or another user's secret — fails to decrypt instead of quietly working. The
   * leading format tag is what lets this shape change later without old rows becoming unreadable.
   */
  private byte[] additionalAuthenticatedData(SecretContext context) {
    String aad =
        "vibecode:vault:v"
            + FORMAT_VERSION
            + "|secret="
            + context.secretId()
            + "|owner="
            + context.ownerUserId()
            + "|purpose="
            + context.purpose().name()
            + "|version="
            + context.version();
    return aad.getBytes(StandardCharsets.UTF_8);
  }

  public EncryptedSecret encrypt(SecretMaterial material, SecretContext context) {
    byte[] plaintext = material.bytes();
    SecretKey dataKey = null;
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(DATA_KEY_BITS, random);
      dataKey = generator.generateKey();

      // A fresh nonce per encryption. Reusing one with the same key would break GCM outright,
      // which is why it is generated here and never derived from anything.
      byte[] nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(additionalAuthenticatedData(context));
      byte[] ciphertext = cipher.doFinal(plaintext);

      return new EncryptedSecret(
          ciphertext,
          nonce,
          keyProvider.wrapKey(dataKey),
          ALGORITHM,
          FORMAT_VERSION,
          keyProvider.providerId(),
          keyProvider.keyVersion());
    } catch (VaultCryptographyException alreadyUniform) {
      throw alreadyUniform;
    } catch (Exception failure) {
      throw new VaultCryptographyException("Could not encrypt the secret", failure);
    } finally {
      Arrays.fill(plaintext, (byte) 0);
      destroy(dataKey);
    }
  }

  /**
   * Recovers the secret.
   *
   * <p>Returns {@link SecretMaterial} rather than a String so the caller has something it can close,
   * and so the value cannot end up in a log by accident.
   */
  public SecretMaterial decrypt(EncryptedSecret encrypted, SecretContext context) {
    SecretKey dataKey = null;
    byte[] plaintext = null;
    try {
      dataKey = keyProvider.unwrapKey(encrypted.wrappedDataEncryptionKey(), encrypted.keyVersion());

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
      cipher.updateAAD(additionalAuthenticatedData(context));
      plaintext = cipher.doFinal(encrypted.ciphertext());

      return SecretMaterial.of(plaintext);
    } catch (Exception failure) {
      // A wrong master key, a tampered ciphertext, a mismatched AAD and a failed unwrap all arrive
      // here, and all produce the same message — including the ones that already carry a vault
      // message of their own, which is why nothing is rethrown unchanged. "Could not unwrap the
      // data encryption key" would tell a caller their key is wrong rather than their data, and
      // that distinction is the whole of an oracle.
      throw new VaultCryptographyException("Could not decrypt the secret", failure);
    } finally {
      if (plaintext != null) {
        Arrays.fill(plaintext, (byte) 0);
      }
      destroy(dataKey);
    }
  }

  private void destroy(SecretKey key) {
    if (key == null) {
      return;
    }
    try {
      key.destroy();
    } catch (Exception notSupported) {
      // SecretKeySpec does not support destruction. Nothing further can be done here, and the key
      // is short-lived and unreferenced from this point.
    }
  }
}
