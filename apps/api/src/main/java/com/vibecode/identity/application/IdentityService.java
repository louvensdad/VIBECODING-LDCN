package com.vibecode.identity.application;

import com.vibecode.identity.domain.CurrentUser;
import com.vibecode.identity.domain.EmailAddress;
import com.vibecode.identity.domain.PasswordPolicy;
import com.vibecode.identity.domain.PlatformRole;
import com.vibecode.identity.domain.User;
import com.vibecode.identity.infrastructure.UserRepository;
import com.vibecode.shared.domain.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and user lookup.
 *
 * <p>The raw password exists inside {@link #register} and nowhere else: it is validated, encoded,
 * and the encoded value is what reaches the entity. It is never stored in a field, returned, or
 * passed to the logger.
 */
@Service
@Transactional
public class IdentityService {

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final SecurityEventLogger securityEvents;

  public IdentityService(
      UserRepository users, PasswordEncoder passwordEncoder, SecurityEventLogger securityEvents) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.securityEvents = securityEvents;
  }

  public User register(String rawEmail, String rawPassword, String displayName) {
    EmailAddress email = EmailAddress.of(rawEmail);
    PasswordPolicy.validate(rawPassword);

    // A unique index backs this: two concurrent registrations cannot both win.
    if (users.existsByEmail(email.value())) {
      throw new EmailAlreadyRegisteredException();
    }

    User user =
        new User(
            email,
            passwordEncoder.encode(rawPassword),
            resolveDisplayName(displayName, email),
            PlatformRole.USER);
    User saved = users.save(user);
    securityEvents.registered(saved.getId(), saved.getEmail());
    return saved;
  }

  /** Falls back to the local part of the address rather than leaving the name blank. */
  private String resolveDisplayName(String displayName, EmailAddress email) {
    if (displayName != null && !displayName.isBlank()) {
      return displayName.trim();
    }
    return email.value().substring(0, email.value().indexOf('@'));
  }

  @Transactional(readOnly = true)
  public User require(UUID id) {
    return users
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
  }

  @Transactional(readOnly = true)
  public User require(CurrentUser currentUser) {
    return require(currentUser.id());
  }

  /** Called after a successful authentication. */
  public void recordLogin(UUID userId) {
    users.findById(userId).ifPresent(User::recordLogin);
  }
}
