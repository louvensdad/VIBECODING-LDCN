package com.vibecode.support;

import com.vibecode.identity.application.IdentityService;
import com.vibecode.identity.domain.User;
import com.vibecode.identity.infrastructure.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Creates real users and puts a real authenticated identity in place.
 *
 * <p>Tests authenticate as an actual persisted user rather than a stub, so ownership checks run
 * against the same rows production would use. Nothing here weakens the security configuration.
 */
@Component
public class TestIdentity {

  /** Long enough for the password policy, and obviously not a credential anyone would reuse. */
  public static final String PASSWORD = "correct horse battery staple";

  private final IdentityService identity;

  public TestIdentity(IdentityService identity) {
    this.identity = identity;
  }

  /** Registers a user with a unique address so parallel or repeated tests never collide. */
  public User createUser(String label) {
    return identity.register(
        label + "-" + UUID.randomUUID() + "@example.com", PASSWORD, label);
  }

  /** Registers a user and makes them the authenticated caller for service-level tests. */
  public User createAndAuthenticate(String label) {
    User user = createUser(label);
    authenticateAs(user);
    return user;
  }

  public void authenticateAs(User user) {
    AuthenticatedUser principal = new AuthenticatedUser(user);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            principal, null, principal.getAuthorities()));
    SecurityContextHolder.setContext(context);
  }

  public void clear() {
    SecurityContextHolder.clearContext();
  }

  /** Authenticates a MockMvc request as the given user, through the normal security plumbing. */
  public static RequestPostProcessor as(User user) {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .user(new AuthenticatedUser(user));
  }
}
