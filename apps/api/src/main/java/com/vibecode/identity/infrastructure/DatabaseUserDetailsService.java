package com.vibecode.identity.infrastructure;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibecode.identity.domain.EmailAddress;

/**
 * Loads a user for authentication.
 *
 * <p>An unknown email raises the same exception Spring Security raises for a bad password, and the
 * login endpoint reports both identically — otherwise the response would confirm which addresses
 * have accounts.
 */
@Service
@Transactional(readOnly = true)
public class DatabaseUserDetailsService implements UserDetailsService {

  private final UserRepository users;

  public DatabaseUserDetailsService(UserRepository users) {
    this.users = users;
  }

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    String normalized;
    try {
      normalized = EmailAddress.of(email).value();
    } catch (IllegalArgumentException malformed) {
      // A malformed address cannot match a stored one; say nothing more than "not found".
      throw new UsernameNotFoundException("Invalid credentials");
    }
    return users
        .findByEmail(normalized)
        .map(AuthenticatedUser::new)
        .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }
}
