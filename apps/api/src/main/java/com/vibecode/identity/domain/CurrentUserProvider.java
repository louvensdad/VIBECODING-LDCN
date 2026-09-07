package com.vibecode.identity.domain;

import java.util.Optional;

/**
 * Port that hands the application layer the authenticated identity.
 *
 * <p>The adapter that reads the security context lives in infrastructure. This interface is the
 * only thing the rest of the application knows about authentication.
 */
public interface CurrentUserProvider {

  Optional<CurrentUser> current();

  /**
   * @throws NotAuthenticatedException when nobody is authenticated. Used where a request has
   *     already passed the security filter chain, so absence means a wiring mistake rather than an
   *     anonymous caller.
   */
  default CurrentUser require() {
    return current().orElseThrow(NotAuthenticatedException::new);
  }

  /** Mapped to HTTP 401. */
  class NotAuthenticatedException extends RuntimeException {

    public NotAuthenticatedException() {
      super("Authentication is required");
    }
  }
}
