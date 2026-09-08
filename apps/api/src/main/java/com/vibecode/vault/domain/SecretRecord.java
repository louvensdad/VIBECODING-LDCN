package com.vibecode.vault.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A stored secret, as an identity and a lifecycle. Holds no cryptographic material itself.
 *
 * <p>The material lives on {@link SecretVersion}, so rotation adds a row rather than overwriting
 * one — the previous version stays readable until the new one is safely persisted.
 */
@Entity
@Table(name = "vault_secrets")
public class SecretRecord {

  @Id private UUID id;

  @Column(name = "owner_user_id", nullable = false)
  private UUID ownerUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private SecretPurpose purpose;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private SecretStatus status;

  /**
   * The version currently in force.
   *
   * <p>Keeping the pointer here rather than a flag on each version is what makes rotation safe.
   * The replacement is written and flushed while this column still names the old one, and the
   * switch is a single-column update: there is no moment where the secret has two active versions,
   * and no moment where it has none. A flag per version cannot offer both — enforcing uniqueness
   * on it forbids the safe write order, and not enforcing it makes the invariant a convention.
   */
  @Column(name = "active_version_id")
  private UUID activeVersionId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** For JPA only. */
  protected SecretRecord() {}

  public SecretRecord(UUID ownerUserId, SecretPurpose purpose) {
    this.id = UUID.randomUUID();
    this.ownerUserId = ownerUserId;
    this.purpose = purpose;
    this.status = SecretStatus.ACTIVE;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  /** Puts a version in force. The only way the active pointer moves. */
  public void activateVersion(UUID versionId) {
    this.activeVersionId = versionId;
    touch();
  }

  public void touch() {
    this.updatedAt = Instant.now();
  }

  public void destroy() {
    this.status = SecretStatus.DESTROYED;
    this.activeVersionId = null;
    touch();
  }

  public SecretReference reference() {
    return new SecretReference(id, purpose);
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerUserId() {
    return ownerUserId;
  }

  public SecretPurpose getPurpose() {
    return purpose;
  }

  public SecretStatus getStatus() {
    return status;
  }

  public UUID getActiveVersionId() {
    return activeVersionId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Nothing sensitive: this entity never holds any. */
  @Override
  public String toString() {
    return "SecretRecord[id=" + id + ", purpose=" + purpose + ", status=" + status + "]";
  }
}
