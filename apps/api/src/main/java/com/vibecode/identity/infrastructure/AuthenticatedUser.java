package com.vibecode.identity.infrastructure;

import com.vibecode.identity.domain.CurrentUser;
import com.vibecode.identity.domain.PlatformRole;
import com.vibecode.identity.domain.User;
import com.vibecode.identity.domain.UserStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security's view of a user.
 *
 * <p>Holds the id and role so authorization never needs another query, and keeps the password hash
 * only for the framework's own comparison — {@link #eraseCredentials()} drops it as soon as
 * authentication succeeds, so the hash does not sit in the session.
 */
public class AuthenticatedUser implements UserDetails {

  private final UUID id;
  private final String email;
  private final PlatformRole role;
  private final UserStatus status;
  private String passwordHash;

  public AuthenticatedUser(User user) {
    this.id = user.getId();
    this.email = user.getEmail();
    this.role = user.getRole();
    this.status = user.getStatus();
    this.passwordHash = user.getPasswordHash();
  }

  public CurrentUser toCurrentUser() {
    return new CurrentUser(id, email, role);
  }

  public UUID getId() {
    return id;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority(role.authority()));
  }

  @Override
  public String getPassword() {
    return passwordHash;
  }

  @Override
  public String getUsername() {
    return email;
  }

  @Override
  public boolean isAccountNonLocked() {
    return status != UserStatus.LOCKED;
  }

  @Override
  public boolean isEnabled() {
    return status.canAuthenticate();
  }

  public void eraseCredentials() {
    this.passwordHash = null;
  }

  /** Never includes the hash. */
  @Override
  public String toString() {
    return "AuthenticatedUser[id=" + id + ", email=" + email + "]";
  }
}
