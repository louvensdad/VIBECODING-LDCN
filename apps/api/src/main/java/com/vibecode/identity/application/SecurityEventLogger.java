package com.vibecode.identity.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Structured record of security-relevant events.
 *
 * <p>Deliberately a logger and not a table: this phase needs the events to exist and to be
 * greppable, not a retention policy and a query API. It becomes a store when something actually
 * reads it.
 *
 * <p>What is never written here: passwords, password hashes, raw session ids, CSRF tokens. An email
 * is recorded on a failed login because that is the whole value of the record — without it a burst
 * of failures cannot be attributed to a target — and it is the address the caller typed, not proof
 * that an account exists.
 */
@Component
public class SecurityEventLogger {

  private static final Logger log = LoggerFactory.getLogger("com.vibecode.security");

  public enum SecurityEvent {
    REGISTERED,
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    ACCESS_DENIED
  }

  public void loginSucceeded(UUID userId, String email) {
    log.info("event={} userId={} email={}", SecurityEvent.LOGIN_SUCCESS, userId, email);
  }

  /** No distinction between "no such account" and "wrong password" is recorded in the reason. */
  public void loginFailed(String email, String reason) {
    log.warn("event={} email={} reason={}", SecurityEvent.LOGIN_FAILURE, email, reason);
  }

  public void registered(UUID userId, String email) {
    log.info("event={} userId={} email={}", SecurityEvent.REGISTERED, userId, email);
  }

  public void loggedOut(UUID userId) {
    log.info("event={} userId={}", SecurityEvent.LOGOUT, userId);
  }

  /**
   * Records a denied resource access. The resource id is included because it is the caller's own
   * input, and knowing which id was probed is what makes an enumeration attempt visible.
   */
  public void accessDenied(UUID userId, String resourceType, UUID resourceId) {
    log.warn(
        "event={} userId={} resourceType={} resourceId={}",
        SecurityEvent.ACCESS_DENIED,
        userId,
        resourceType,
        resourceId);
  }
}
