package com.vibecode.identity.domain;

/**
 * What a user is on the platform.
 *
 * <p>This is <em>not</em> authorization for any particular project. {@code USER} does not mean "may
 * read any project", and {@code ADMIN} does not either — access to a project is decided by {@code
 * ProjectAccessPolicy}, which today grants it to the owner alone.
 */
public enum PlatformRole {
  USER,
  ADMIN;

  /** The authority name Spring Security uses. */
  public String authority() {
    return "ROLE_" + name();
  }
}
