package com.vibecode.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A person who can sign in.
 *
 * <p>The entity never sees a plaintext password. It is constructed with an already-encoded hash, so
 * there is no field, parameter or getter anywhere on this class through which a raw password could
 * travel or be accidentally logged.
 */
@Entity
@Table(name = "users")
public class User {

  @Id private UUID id;

  /** Stored normalized. The unique index is on this value. */
  @Column(nullable = false, length = 320)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 200)
  private String passwordHash;

  @Column(name = "display_name", nullable = false, length = 80)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private PlatformRole role;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  /** For JPA only. */
  protected User() {}

  /**
   * @param encodedPassword an already-hashed password. Passing a plaintext value here would store
   *     it verbatim, which is why the only caller is the registration service, right after the
   *     encoder.
   */
  public User(
      EmailAddress email, String encodedPassword, String displayName, PlatformRole role) {
    if (encodedPassword == null || encodedPassword.isBlank()) {
      throw new IllegalArgumentException("An encoded password is required");
    }
    this.id = UUID.randomUUID();
    this.email = email.value();
    this.passwordHash = encodedPassword;
    this.displayName = displayName;
    this.status = UserStatus.ACTIVE;
    this.role = role;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public void recordLogin() {
    this.lastLoginAt = Instant.now();
    this.updatedAt = this.lastLoginAt;
  }

  public void changeStatus(UserStatus newStatus) {
    this.status = newStatus;
    this.updatedAt = Instant.now();
  }

  public boolean canAuthenticate() {
    return status.canAuthenticate();
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  /**
   * The stored hash. Used by the authentication provider only — it must never reach a DTO, a log
   * line or a response body.
   */
  public String getPasswordHash() {
    return passwordHash;
  }

  public String getDisplayName() {
    return displayName;
  }

  public UserStatus getStatus() {
    return status;
  }

  public PlatformRole getRole() {
    return role;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  /** Deliberately excludes the hash, so an accidental log of a User cannot leak it. */
  @Override
  public String toString() {
    return "User[id=" + id + ", email=" + email + ", status=" + status + ", role=" + role + "]";
  }
}
