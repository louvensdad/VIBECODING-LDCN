package com.vibecode.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.vault.infrastructure.LocalKeyEncryptionProvider;
import com.vibecode.vault.infrastructure.LocalKeyEncryptionProvider.VaultStartupException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens when the master key is wrong.
 *
 * <p>The answer is always the same: refuse to start. A vault that falls back to a generated key
 * comes up healthy and silently orphans every secret already stored under the real one — the
 * failure would only be discovered later, by a user whose credential no longer works, with no way
 * left to recover it.
 */
class VaultMasterKeyConfigurationTest {

  private static final String VALID =
      Base64.getEncoder()
          .encodeToString("vibecode-test-vault-key-32-bytes".getBytes(StandardCharsets.UTF_8));

  @Test
  @DisplayName("A missing master key stops startup instead of generating one")
  void missingKeyFailsClosed() {
    assertThatThrownBy(() -> new LocalKeyEncryptionProvider(null, "v1"))
        .isInstanceOf(VaultStartupException.class)
        .hasMessageContaining("VIBECODE_VAULT_MASTER_KEY");

    assertThatThrownBy(() -> new LocalKeyEncryptionProvider("   ", "v1"))
        .isInstanceOf(VaultStartupException.class);
  }

  @Test
  @DisplayName("A key that is not base64 stops startup")
  void malformedKeyFailsClosed() {
    assertThatThrownBy(() -> new LocalKeyEncryptionProvider("not base64 !!!", "v1"))
        .isInstanceOf(VaultStartupException.class)
        .hasMessageContaining("base64");
  }

  @Test
  @DisplayName("A key of the wrong length stops startup rather than being padded or hashed")
  void wrongLengthFailsClosed() {
    String tooShort =
        Base64.getEncoder().encodeToString("only-sixteen-b16".getBytes(StandardCharsets.UTF_8));

    // Stretching a short key to 32 bytes would let a weak key look like a strong one.
    assertThatThrownBy(() -> new LocalKeyEncryptionProvider(tooShort, "v1"))
        .isInstanceOf(VaultStartupException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  @DisplayName("No failure message ever contains the key material it rejected")
  void failureMessagesDoNotEchoTheKey() {
    String wrongLength =
        Base64.getEncoder().encodeToString("only-sixteen-b16".getBytes(StandardCharsets.UTF_8));

    String message =
        catchMessage(() -> new LocalKeyEncryptionProvider(wrongLength, "v1"));

    // A startup error goes straight into logs and, often, into a screenshot in a chat window.
    // Quoting the value there would publish it more effectively than any attack.
    assertThat(message).doesNotContain(wrongLength).doesNotContain("only-sixteen");
    assertThat(catchMessage(() -> new LocalKeyEncryptionProvider("not base64 !!!", "v1")))
        .doesNotContain("not base64 !!!");
  }

  @Test
  @DisplayName("A correct key is accepted")
  void validKeyIsAccepted() {
    LocalKeyEncryptionProvider provider = new LocalKeyEncryptionProvider(VALID, "test-v1");

    assertThat(provider.providerId()).isEqualTo("LOCAL");
    assertThat(provider.keyVersion()).isEqualTo("test-v1");
  }

  private String catchMessage(Runnable operation) {
    try {
      operation.run();
      throw new AssertionError("Expected startup to fail");
    } catch (VaultStartupException failure) {
      return failure.getMessage();
    }
  }
}
