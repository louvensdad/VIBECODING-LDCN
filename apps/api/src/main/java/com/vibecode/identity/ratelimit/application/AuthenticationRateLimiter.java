package com.vibecode.identity.ratelimit.application;

import com.vibecode.audit.application.AuditService;
import com.vibecode.audit.domain.AuditEventType;
import com.vibecode.identity.ratelimit.domain.RateLimitDecision;
import com.vibecode.identity.ratelimit.domain.RateLimitExceededException;
import com.vibecode.identity.ratelimit.domain.RateLimitKey;
import com.vibecode.identity.ratelimit.domain.RateLimitPolicy;
import com.vibecode.identity.ratelimit.domain.RateLimitScope;
import com.vibecode.identity.ratelimit.domain.RateLimitStore;
import com.vibecode.identity.ratelimit.infrastructure.AuthRateLimitProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Coordinates the checks. Controllers ask this; they contain no counting logic of their own.
 *
 * <p>Both buckets must allow an attempt. Checking origin first means a client already over its
 * volume limit is turned away without the identifier bucket being touched, so a flood cannot also
 * be used to drain the buckets of accounts it names.
 */
@Service
public class AuthenticationRateLimiter {

  private final AuthRateLimitProperties properties;
  private final RateLimitStore store;
  private final IdentifierFingerprint fingerprint;
  private final AuditService audit;
  private final Clock clock;
  private final MeterRegistry meters;

  public AuthenticationRateLimiter(
      AuthRateLimitProperties properties,
      RateLimitStore store,
      IdentifierFingerprint fingerprint,
      AuditService audit,
      Clock clock,
      MeterRegistry meters) {
    this.properties = properties;
    this.store = store;
    this.fingerprint = fingerprint;
    this.audit = audit;
    this.clock = clock;
    this.meters = meters;
  }

  /** Volume from one client. Cheap: no body parsing, no database. */
  public void checkOrigin(RateLimitScope scope, String origin) {
    enforce(scope, origin, "origin");
  }

  /**
   * Pressure on one identifier.
   *
   * <p>The key comes from what the caller typed, not from a user that was looked up, so an address
   * with no account consumes its attempt exactly like one with an account. Anything else would let
   * the limiter answer "does this account exist?".
   */
  public void checkIdentifier(RateLimitScope scope, String rawIdentifier) {
    enforce(scope, fingerprint.of(rawIdentifier), "identifier");
  }

  /**
   * Called after a successful login.
   *
   * <p>Only the account bucket is cleared. Clearing the origin bucket too would let an attacker who
   * holds one valid account interleave real logins with guesses to keep resetting their volume
   * allowance — the origin limit exists precisely to bound that.
   */
  public void recordSuccessfulLogin(String rawIdentifier) {
    if (!properties.isEnabled()) {
      return;
    }
    store.reset(new RateLimitKey(RateLimitScope.LOGIN_ACCOUNT, fingerprint.of(rawIdentifier)));
  }

  private void enforce(RateLimitScope scope, String rawKeyMaterial, String dimension) {
    if (!properties.isEnabled()) {
      return;
    }
    RateLimitPolicy policy = properties.policyFor(scope);
    RateLimitKey key = new RateLimitKey(scope, rawKeyMaterial);
    Instant now = clock.instant();

    if (store.tryConsume(key, policy, now)) {
      return;
    }

    RateLimitDecision decision = RateLimitDecision.denied(policy, now);
    recordDenial(decision, dimension);
    throw new RateLimitExceededException(decision);
  }

  /**
   * Records the refusal.
   *
   * <p>Metadata is limited to the scope, the policy and the endpoint. No address, no identifier, no
   * fingerprint: an audit trail that stores who was targeted becomes a list worth stealing.
   */
  private void recordDenial(RateLimitDecision decision, String dimension) {
    AuditEventType eventType =
        decision.scope() == RateLimitScope.REGISTER_ORIGIN
                || decision.scope() == RateLimitScope.REGISTER_IDENTIFIER
            ? AuditEventType.REGISTRATION_RATE_LIMITED
            : AuditEventType.AUTH_RATE_LIMITED;

    audit.recordWithActor(
        null,
        null,
        eventType,
        "AUTH_ENDPOINT",
        decision.scope().name(),
        "DENIED",
        "policyId=" + decision.policyId() + " dimension=" + dimension);

    // Tags are the scope and policy only. An address or an email here would put unbounded
    // cardinality — and personal data — into the metrics backend.
    Counter.builder("auth.rate_limit.denied")
        .tag("scope", decision.scope().name())
        .tag("policy", decision.policyId())
        .register(meters)
        .increment();
  }
}
