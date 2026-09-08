package com.vibecode.provider.domain;

import com.vibecode.vault.domain.SecretReference;
import com.vibecode.vault.domain.SecretPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A connection to a model provider.
 *
 * <p>It represents the connection, not the credential. There is no {@code apiKey} field here and
 * there must never be one: the entity holds a {@link SecretReference} — an id — and the material it
 * points at lives encrypted in the vault. That separation is what lets this row be read, listed,
 * serialised into a DTO and written to an audit trail without any of those paths touching a secret.
 */
@Entity
@Table(name = "provider_accounts")
public class ProviderAccount {

  @Id private UUID id;

  @Column(name = "owner_user_id", nullable = false)
  private UUID ownerUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private ProviderId provider;

  @Column(name = "display_name", nullable = false, length = 120)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(name = "authentication_type", nullable = false, length = 40)
  private AuthenticationType authenticationType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private ProviderAccountStatus status;

  /** A pointer into the vault. Never the credential. */
  @Column(name = "credential_secret_id")
  private UUID credentialSecretId;

  @Column(name = "credential_updated_at")
  private Instant credentialUpdatedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** For JPA only. */
  protected ProviderAccount() {}

  public ProviderAccount(
      UUID ownerUserId,
      ProviderId provider,
      String displayName,
      AuthenticationType authenticationType) {
    this.id = UUID.randomUUID();
    this.ownerUserId = ownerUserId;
    this.provider = provider;
    this.displayName = displayName;
    this.authenticationType = authenticationType;
    this.status = ProviderAccountStatus.PENDING_CREDENTIAL;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  /**
   * Records that a credential now exists in the vault.
   *
   * <p>The status says stored and unverified, never connected: nothing has been sent to the
   * provider, so nothing is known about whether the credential works.
   */
  public void attachCredential(SecretReference reference) {
    this.credentialSecretId = reference.secretId();
    this.credentialUpdatedAt = Instant.now();
    if (this.status != ProviderAccountStatus.DISABLED) {
      this.status = ProviderAccountStatus.CREDENTIAL_STORED_UNVERIFIED;
    }
    touch();
  }

  /** Rotation keeps the same secret id; only the timestamp moves. */
  public void recordCredentialRotation() {
    this.credentialUpdatedAt = Instant.now();
    touch();
  }

  public void detachCredential() {
    this.credentialSecretId = null;
    this.credentialUpdatedAt = null;
    if (this.status != ProviderAccountStatus.DISABLED) {
      this.status = ProviderAccountStatus.PENDING_CREDENTIAL;
    }
    touch();
  }

  public void disable() {
    this.status = ProviderAccountStatus.DISABLED;
    touch();
  }

  private void touch() {
    this.updatedAt = Instant.now();
  }

  public boolean hasCredential() {
    return credentialSecretId != null;
  }

  public Optional<SecretReference> credentialReference() {
    return Optional.ofNullable(credentialSecretId)
        .map(secretId -> new SecretReference(secretId, SecretPurpose.PROVIDER_API_KEY));
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerUserId() {
    return ownerUserId;
  }

  public ProviderId getProvider() {
    return provider;
  }

  public String getDisplayName() {
    return displayName;
  }

  public AuthenticationType getAuthenticationType() {
    return authenticationType;
  }

  public ProviderAccountStatus getStatus() {
    return status;
  }

  public Instant getCredentialUpdatedAt() {
    return credentialUpdatedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Safe by construction: there is nothing secret on this entity to leak. */
  @Override
  public String toString() {
    return "ProviderAccount[id="
        + id
        + ", provider="
        + provider
        + ", status="
        + status
        + ", hasCredential="
        + hasCredential()
        + "]";
  }
}
