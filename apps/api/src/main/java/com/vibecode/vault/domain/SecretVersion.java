package com.vibecode.vault.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * One encrypted version of a secret.
 *
 * <p>Everything needed to decrypt it later is recorded next to it — algorithm, format version, key
 * provider and key version. Relying on "we know we used AES today" is how ciphertext becomes
 * unreadable after a migration.
 */
@Entity
@Table(name = "vault_secret_versions")
public class SecretVersion {

  @Id private UUID id;

  @Column(name = "secret_id", nullable = false)
  private UUID secretId;

  @Column(name = "version_number", nullable = false)
  private int versionNumber;

  @Column(nullable = false)
  private byte[] ciphertext;

  @Column(nullable = false)
  private byte[] nonce;

  @Column(name = "wrapped_data_key", nullable = false)
  private byte[] wrappedDataEncryptionKey;

  @Column(nullable = false, length = 40)
  private String algorithm;

  @Column(name = "format_version", nullable = false)
  private short formatVersion;

  @Column(name = "key_provider", nullable = false, length = 40)
  private String keyProvider;

  @Column(name = "key_version", nullable = false, length = 60)
  private String keyVersion;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private SecretVersionStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "retired_at")
  private Instant retiredAt;

  /** For JPA only. */
  protected SecretVersion() {}

  public SecretVersion(
      UUID secretId,
      int versionNumber,
      byte[] ciphertext,
      byte[] nonce,
      byte[] wrappedDataEncryptionKey,
      String algorithm,
      short formatVersion,
      String keyProvider,
      String keyVersion) {
    this.id = UUID.randomUUID();
    this.secretId = secretId;
    this.versionNumber = versionNumber;
    this.ciphertext = ciphertext;
    this.nonce = nonce;
    this.wrappedDataEncryptionKey = wrappedDataEncryptionKey;
    this.algorithm = algorithm;
    this.formatVersion = formatVersion;
    this.keyProvider = keyProvider;
    this.keyVersion = keyVersion;
    this.status = SecretVersionStatus.ACTIVE;
    this.createdAt = Instant.now();
  }

  public void retire() {
    this.status = SecretVersionStatus.RETIRED;
    this.retiredAt = Instant.now();
  }

  /**
   * Makes this version permanently unreadable.
   *
   * <p>The wrapped data key is overwritten, which is what actually destroys it: without the data
   * key the ciphertext is unrecoverable even to someone holding the master key. The ciphertext
   * bytes are cleared too, and the row is kept so the audit trail still refers to something.
   */
  public void destroy() {
    Arrays.fill(this.wrappedDataEncryptionKey, (byte) 0);
    Arrays.fill(this.ciphertext, (byte) 0);
    this.status = SecretVersionStatus.DESTROYED;
    this.retiredAt = Instant.now();
  }

  /**
   * Whether this version is readable.
   *
   * <p>Not the same question as "is this the version in force" — that one is answered by
   * {@link SecretRecord#getActiveVersionId()}, and only there.
   */
  public boolean isActive() {
    return status == SecretVersionStatus.ACTIVE;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSecretId() {
    return secretId;
  }

  public int getVersionNumber() {
    return versionNumber;
  }

  public byte[] getCiphertext() {
    return ciphertext;
  }

  public byte[] getNonce() {
    return nonce;
  }

  public byte[] getWrappedDataEncryptionKey() {
    return wrappedDataEncryptionKey;
  }

  public String getAlgorithm() {
    return algorithm;
  }

  public short getFormatVersion() {
    return formatVersion;
  }

  public String getKeyProvider() {
    return keyProvider;
  }

  public String getKeyVersion() {
    return keyVersion;
  }

  public SecretVersionStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getRetiredAt() {
    return retiredAt;
  }

  /** Never the ciphertext, the nonce or the wrapped key. */
  @Override
  public String toString() {
    return "SecretVersion[id="
        + id
        + ", secret="
        + secretId
        + ", v"
        + versionNumber
        + ", "
        + status
        + "]";
  }
}
