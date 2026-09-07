package com.vibecode.identity.domain;

import java.util.UUID;

/**
 * The authenticated identity, as the application layer sees it.
 *
 * <p>A deliberately small record with no framework types in it. Services take authorization
 * decisions against this, so the domain never has to reach for {@code SecurityContextHolder},
 * {@code HttpServletRequest} or {@code Principal} — and stays testable by passing a value.
 */
public record CurrentUser(UUID id, String email, PlatformRole role) {

  public boolean isAdmin() {
    return role == PlatformRole.ADMIN;
  }
}
