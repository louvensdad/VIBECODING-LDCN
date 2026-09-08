package com.vibecode.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.vault.application.SecretEncryptionService;
import com.vibecode.vault.application.SecretEncryptionService.EncryptedSecret;
import com.vibecode.vault.application.SecretEncryptionService.SecretContext;
import com.vibecode.vault.domain.SecretMaterial;
import com.vibecode.vault.domain.SecretPurpose;
import com.vibecode.vault.domain.VaultCryptographyException;
import com.vibecode.vault.infrastructure.LocalKeyEncryptionProvider;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cryptography itself.
 *
 * <p>Every fixture here is obviously synthetic. No test in this suite uses a credential that would
 * be worth anything to anyone.
 */
class SecretEncryptionServiceTest {

  private static final String CREDENTIAL = "vc_secret_test_928475_example";

  private static final String KEY_A =
      Base64.getEncoder()
          .encodeToString("vibecode-test-vault-key-32-bytes".getBytes(StandardCharsets.UTF_8));
  private static final String KEY_B =
      Base64.getEncoder()
          .encodeToString("vibecode-other-vault-key-32bytes".getBytes(StandardCharsets.UTF_8));

  private final SecretEncryptionService service =
      new SecretEncryptionService(new LocalKeyEncryptionProvider(KEY_A, "test-v1"));

  private final UUID secretId = UUID.randomUUID();
  private final UUID ownerId = UUID.randomUUID();

  private SecretContext context() {
    return new SecretContext(secretId, ownerId, SecretPurpose.PROVIDER_API_KEY, 1);
  }

  private SecretMaterial material() {
    return SecretMaterial.of(CREDENTIAL.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("Encrypts with AES-256-GCM and records how, so old rows stay readable later")
  void algorithmIsRecorded() {
    EncryptedSecret encrypted = service.encrypt(material(), context());

    assertThat(encrypted.algorithm()).isEqualTo("AES-256-GCM");
    assertThat(encrypted.formatVersion()).isEqualTo((short) 1);
    assertThat(encrypted.keyProviderId()).isEqualTo("LOCAL");
    assertThat(encrypted.keyVersion()).isEqualTo("test-v1");
    // 96-bit nonce, the size GCM is specified for.
    assertThat(encrypted.nonce()).hasSize(12);
  }

  @Test
  @DisplayName("Ciphertext does not contain the plaintext in any obvious encoding")
  void ciphertextDoesNotContainPlaintext() {
    EncryptedSecret encrypted = service.encrypt(material(), context());

    assertThat(new String(encrypted.ciphertext(), StandardCharsets.UTF_8))
        .doesNotContain(CREDENTIAL);
    assertThat(new String(encrypted.ciphertext(), StandardCharsets.ISO_8859_1))
        .doesNotContain(CREDENTIAL);
    assertThat(Base64.getEncoder().encodeToString(encrypted.ciphertext()))
        .doesNotContain(
            Base64.getEncoder().encodeToString(CREDENTIAL.getBytes(StandardCharsets.UTF_8)));
    // A ciphertext the same length as the plaintext would mean no authentication tag.
    assertThat(encrypted.ciphertext().length).isGreaterThan(CREDENTIAL.length());
  }

  @Test
  @DisplayName("The same secret encrypted twice produces different bytes")
  void nonceIsRandomPerEncryption() {
    EncryptedSecret first = service.encrypt(material(), context());
    EncryptedSecret second = service.encrypt(material(), context());

    // If these matched, the nonce would be fixed and GCM would be catastrophically broken:
    // two ciphertexts under one key and nonce leak their XOR.
    assertThat(first.nonce()).isNotEqualTo(second.nonce());
    assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    // And a fresh data key per version, so one recovered key does not open the others.
    assertThat(first.wrappedDataEncryptionKey()).isNotEqualTo(second.wrappedDataEncryptionKey());
  }

  @Test
  @DisplayName("Round trip returns exactly the original material")
  void roundTrip() {
    EncryptedSecret encrypted = service.encrypt(material(), context());

    try (SecretMaterial recovered = service.decrypt(encrypted, context())) {
      assertThat(new String(recovered.bytes(), StandardCharsets.UTF_8)).isEqualTo(CREDENTIAL);
    }
  }

  @Test
  @DisplayName("A different master key cannot unwrap the data key")
  void wrongMasterKeyFails() {
    EncryptedSecret encrypted = service.encrypt(material(), context());

    SecretEncryptionService other =
        new SecretEncryptionService(new LocalKeyEncryptionProvider(KEY_B, "test-v1"));

    assertThatThrownBy(() -> other.decrypt(encrypted, context()))
        .isInstanceOf(VaultCryptographyException.class);
  }

  @Test
  @DisplayName("A single flipped bit in the ciphertext is rejected, not decrypted")
  void tamperedCiphertextIsRejected() {
    EncryptedSecret encrypted = service.encrypt(material(), context());
    byte[] tampered = encrypted.ciphertext().clone();
    tampered[0] ^= 0x01;

    EncryptedSecret altered = withCiphertext(encrypted, tampered);

    // This is what authenticated encryption buys. Without the tag this would decrypt to
    // corrupted bytes and be handed to a provider as if it were a credential.
    assertThatThrownBy(() -> service.decrypt(altered, context()))
        .isInstanceOf(VaultCryptographyException.class);
  }

  @Test
  @DisplayName("A row moved to another owner, secret or version no longer decrypts")
  void additionalAuthenticatedDataBindsTheRow() {
    EncryptedSecret encrypted = service.encrypt(material(), context());

    SecretContext otherOwner =
        new SecretContext(secretId, UUID.randomUUID(), SecretPurpose.PROVIDER_API_KEY, 1);
    SecretContext otherSecret =
        new SecretContext(UUID.randomUUID(), ownerId, SecretPurpose.PROVIDER_API_KEY, 1);
    SecretContext otherVersion =
        new SecretContext(secretId, ownerId, SecretPurpose.PROVIDER_API_KEY, 2);

    // Copying a ciphertext row into someone else's secret is a realistic attack against anyone
    // with database write access. The AAD makes it fail instead of quietly working.
    assertThatThrownBy(() -> service.decrypt(encrypted, otherOwner))
        .isInstanceOf(VaultCryptographyException.class);
    assertThatThrownBy(() -> service.decrypt(encrypted, otherSecret))
        .isInstanceOf(VaultCryptographyException.class);
    assertThatThrownBy(() -> service.decrypt(encrypted, otherVersion))
        .isInstanceOf(VaultCryptographyException.class);
  }

  @Test
  @DisplayName("Failures are indistinguishable from one another and never quote the secret")
  void failuresAreUniform() {
    EncryptedSecret encrypted = service.encrypt(material(), context());
    byte[] tampered = encrypted.ciphertext().clone();
    tampered[0] ^= 0x01;
    EncryptedSecret altered = withCiphertext(encrypted, tampered);

    SecretEncryptionService wrongKeyService =
        new SecretEncryptionService(new LocalKeyEncryptionProvider(KEY_B, "test-v1"));

    String wrongKey = catchMessage(() -> wrongKeyService.decrypt(encrypted, context()));
    String tamperedMessage = catchMessage(() -> service.decrypt(altered, context()));

    // Telling a caller which of the two happened would be an oracle: it narrows down whether
    // they have the wrong key or the wrong data.
    assertThat(wrongKey).isEqualTo(tamperedMessage);
    assertThat(wrongKey).doesNotContain(CREDENTIAL).doesNotContain(KEY_A).doesNotContain(KEY_B);
  }

  private EncryptedSecret withCiphertext(EncryptedSecret original, byte[] ciphertext) {
    return new EncryptedSecret(
        ciphertext,
        original.nonce(),
        original.wrappedDataEncryptionKey(),
        original.algorithm(),
        original.formatVersion(),
        original.keyProviderId(),
        original.keyVersion());
  }

  private String catchMessage(Runnable operation) {
    try {
      operation.run();
      throw new AssertionError("Expected the operation to fail");
    } catch (VaultCryptographyException failure) {
      return failure.getMessage();
    }
  }
}
