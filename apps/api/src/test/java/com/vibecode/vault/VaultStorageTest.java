package com.vibecode.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.identity.domain.User;
import com.vibecode.audit.application.AuditService;
import com.vibecode.support.TestIdentity;
import com.vibecode.vault.application.SecretEncryptionService;
import com.vibecode.vault.application.SecretEncryptionService.EncryptedSecret;
import com.vibecode.vault.application.SecretEncryptionService.SecretContext;
import com.vibecode.vault.application.VaultService;
import com.vibecode.vault.domain.SecretMaterial;
import com.vibecode.vault.domain.SecretPurpose;
import com.vibecode.vault.domain.SecretReference;
import com.vibecode.vault.domain.SecretVersion;
import com.vibecode.vault.domain.SecretVersionStatus;
import com.vibecode.vault.domain.VaultCryptographyException;
import com.vibecode.vault.infrastructure.LocalKeyEncryptionProvider;
import com.vibecode.vault.infrastructure.SecretRecordRepository;
import com.vibecode.vault.infrastructure.SecretVersionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The vault against a real database.
 *
 * <p>Not wrapped in a rollback transaction: the point of several of these tests is what is actually
 * committed to the tables, and a rolled-back test would be asserting about rows that never existed.
 */
@SpringBootTest
class VaultStorageTest {

  // Deliberately the same literals ProviderAccountApiTest uses. That test sweeps every audit row
  // for this credential's last four characters, so sharing the value is what makes a masked-suffix
  // leak of *this* fixture caught by anything at all. The suffix is outside the hexadecimal
  // alphabet because the rows it is swept against are full of UUIDs.
  private static final String CREDENTIAL = "vc_anthropic_test_secret_92zqxw";
  private static final String ROTATED = "vc_anthropic_test_secret_92zqxy";

  /** The same synthetic key the test configuration uses. */
  private static final String TEST_KEY =
      Base64.getEncoder()
          .encodeToString("vibecode-test-vault-key-32-bytes".getBytes(StandardCharsets.UTF_8));

  @Autowired VaultService vault;
  @Autowired TestIdentity testIdentity;
  @Autowired DataSource dataSource;
  @Autowired SecretRecordRepository secretRecords;
  @Autowired SecretVersionRepository secretVersions;
  @Autowired AuditService audit;
  @Autowired MeterRegistry meters;

  private User owner;
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    owner = testIdentity.createAndAuthenticate("vault-owner");
    jdbc = new JdbcTemplate(dataSource);
  }

  @AfterEach
  void tearDown() {
    testIdentity.clear();
  }

  private SecretMaterial material(String value) {
    return SecretMaterial.of(value.getBytes(StandardCharsets.UTF_8));
  }

  private SecretReference store(String value) {
    return vault.store(owner.getId(), SecretPurpose.PROVIDER_API_KEY, material(value));
  }

  @Test
  @DisplayName("Storing returns a reference and nothing that resembles the credential")
  void storeReturnsOnlyAReference() {
    SecretReference reference = store(CREDENTIAL);

    assertThat(reference.secretId()).isNotNull();
    assertThat(reference.purpose()).isEqualTo(SecretPurpose.PROVIDER_API_KEY);
    // A reference is an id and a purpose. There is no accessor on it that yields material.
    assertThat(reference.toString()).doesNotContain(CREDENTIAL);
  }

  @Test
  @DisplayName("No column in the database contains the credential in the clear")
  void databaseHoldsNoPlaintext() {
    store(CREDENTIAL);

    // Every text column of both vault tables, concatenated. If the credential were being written
    // anywhere as a string — a debug column, a status, a mistaken metadata field — it appears here.
    List<String> textColumns =
        jdbc.queryForList(
            "SELECT CAST(purpose AS VARCHAR) || CAST(status AS VARCHAR) FROM vault_secrets",
            String.class);
    assertThat(textColumns).noneMatch(value -> value.contains(CREDENTIAL));

    List<String> versionText =
        jdbc.queryForList(
            "SELECT algorithm || key_provider || key_version || CAST(status AS VARCHAR) "
                + "FROM vault_secret_versions",
            String.class);
    assertThat(versionText).noneMatch(value -> value.contains(CREDENTIAL));

    // And the binary columns, read as raw bytes rather than trusting that they look opaque.
    List<byte[]> blobs =
        jdbc.query(
            "SELECT ciphertext, nonce, wrapped_data_key FROM vault_secret_versions",
            (rs, row) -> {
              byte[] ciphertext = rs.getBytes(1);
              byte[] nonce = rs.getBytes(2);
              byte[] wrapped = rs.getBytes(3);
              byte[] all = new byte[ciphertext.length + nonce.length + wrapped.length];
              System.arraycopy(ciphertext, 0, all, 0, ciphertext.length);
              System.arraycopy(nonce, 0, all, ciphertext.length, nonce.length);
              System.arraycopy(wrapped, 0, all, ciphertext.length + nonce.length, wrapped.length);
              return all;
            });

    assertThat(blobs).isNotEmpty();
    for (byte[] blob : blobs) {
      assertThat(new String(blob, StandardCharsets.UTF_8)).doesNotContain(CREDENTIAL);
      assertThat(new String(blob, StandardCharsets.ISO_8859_1)).doesNotContain(CREDENTIAL);
    }
  }

  @Test
  @DisplayName("The stored material can be recovered, but only inside a callback")
  void withSecretRecoversTheMaterial() {
    SecretReference reference = store(CREDENTIAL);

    assertThat(read(reference)).isEqualTo(CREDENTIAL);
  }

  @Test
  @DisplayName("Rotation adds a version, retires the old one and leaves exactly one active")
  void rotationKeepsOneActiveVersion() {
    SecretReference reference = store(CREDENTIAL);

    vault.rotate(reference, material(ROTATED));

    List<SecretVersion> versions = vault.versionsOf(reference);
    assertThat(versions).hasSize(2);
    assertThat(versions.stream().filter(SecretVersion::isActive)).hasSize(1);

    SecretVersion active = versions.stream().filter(SecretVersion::isActive).findFirst().orElseThrow();
    assertThat(active.getVersionNumber()).isEqualTo(2);

    SecretVersion retired =
        versions.stream().filter(version -> !version.isActive()).findFirst().orElseThrow();
    assertThat(retired.getStatus()).isEqualTo(SecretVersionStatus.RETIRED);
    assertThat(retired.getRetiredAt()).isNotNull();

    // Reading now yields the new value, not the old one.
    assertThat(read(reference)).isEqualTo(ROTATED);
  }

  @Test
  @DisplayName("A rotation that fails leaves the previous credential working")
  void failedRotationDoesNotDestroyTheOldVersion() {
    SecretReference reference = store(CREDENTIAL);

    // A vault whose encryption fails on the way in. This is the case that decides whether the
    // ordering in rotate() is right: if the old version were retired first, a user whose rotation
    // errored would be left with no working credential and no way back to the old one.
    VaultService failing =
        new VaultService(
            secretRecords,
            secretVersions,
            new SecretEncryptionService(new LocalKeyEncryptionProvider(TEST_KEY, "test-v1")) {
              @Override
              public EncryptedSecret encrypt(SecretMaterial material, SecretContext context) {
                throw new VaultCryptographyException("Simulated encryption failure");
              }
            },
            audit,
            meters);

    assertThatThrownBy(() -> failing.rotate(reference, material(ROTATED)))
        .isInstanceOf(VaultCryptographyException.class);

    assertThat(vault.hasActiveSecret(reference)).isTrue();
    assertThat(read(reference)).isEqualTo(CREDENTIAL);
  }

  @Test
  @DisplayName("Destroying overwrites the wrapped key, so the ciphertext cannot be recovered")
  void destroyMakesMaterialUnrecoverable() {
    SecretReference reference = store(CREDENTIAL);

    vault.destroy(reference);

    assertThat(vault.hasActiveSecret(reference)).isFalse();
    assertThatThrownBy(
            () -> vault.withSecret(reference, material -> new String(material.bytes())))
        .isInstanceOf(VaultCryptographyException.class);

    // The row is kept so the audit trail still points at something, but every byte that could
    // decrypt it is gone. Holding the master key is no longer enough.
    List<byte[]> wrapped =
        jdbc.query(
            "SELECT wrapped_data_key FROM vault_secret_versions WHERE secret_id = ?",
            (rs, row) -> rs.getBytes(1),
            reference.secretId());
    assertThat(wrapped).isNotEmpty();
    for (byte[] key : wrapped) {
      for (byte b : key) {
        assertThat(b).isZero();
      }
    }
  }

  @Test
  @DisplayName("A reference to a destroyed secret is reported the same way as an unknown one")
  void destroyedAndUnknownLookAlike() {
    SecretReference reference = store(CREDENTIAL);
    vault.destroy(reference);

    SecretReference neverExisted =
        new SecretReference(java.util.UUID.randomUUID(), SecretPurpose.PROVIDER_API_KEY);

    String destroyedMessage =
        catchMessage(() -> vault.withSecret(reference, material -> ""));
    String unknownMessage =
        catchMessage(() -> vault.withSecret(neverExisted, material -> ""));

    // Distinguishing them would confirm that a given secret id once existed.
    assertThat(destroyedMessage).isEqualTo(unknownMessage);
  }

  /** Reads the material back through the only door the vault offers. */
  private String read(SecretReference reference) {
    return vault.withSecret(
        reference, material -> new String(material.bytes(), StandardCharsets.UTF_8));
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
