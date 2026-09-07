package com.vibecode.identity.infrastructure;

import com.vibecode.identity.domain.CurrentUser;
import com.vibecode.identity.domain.CurrentUserProvider;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The one place that reads the Spring Security context.
 *
 * <p>Everything above this adapter works with {@link CurrentUser}, so authentication can change
 * shape — tokens, another provider — without the application layer noticing.
 */
@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

  @Override
  public Optional<CurrentUser> current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }
    if (authentication.getPrincipal() instanceof AuthenticatedUser user) {
      return Optional.of(user.toCurrentUser());
    }
    // Anonymous authentication has a String principal; it is not a user.
    return Optional.empty();
  }
}
