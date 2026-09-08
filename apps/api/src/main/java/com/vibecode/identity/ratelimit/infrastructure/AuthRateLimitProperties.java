package com.vibecode.identity.ratelimit.infrastructure;

import com.vibecode.identity.ratelimit.domain.RateLimitPolicy;
import com.vibecode.identity.ratelimit.domain.RateLimitScope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every limit, in configuration rather than scattered through Java.
 *
 * <p>Defaults are deliberately generous enough that a person who forgot their password does not
 * meet them, and tight enough that a script does. They are a starting point to tune against real
 * traffic, not a measured optimum.
 */
@ConfigurationProperties(prefix = "vibecode.security.auth-rate-limit")
public class AuthRateLimitProperties {

  /** Turning this off disables the limiter entirely. Present for local debugging only. */
  private boolean enabled = true;

  /**
   * Peers whose {@code X-Forwarded-For} may be believed. Empty by default: see {@link
   * ClientOriginResolver} for why trusting the wrong hop silently disables origin limiting.
   */
  private List<String> trustedProxies = new ArrayList<>();

  /**
   * Upper bound on buckets held in memory. Reached only under an attack that invents identifiers;
   * the least recently used are evicted.
   */
  private int maxTrackedKeys = 50_000;

  private Endpoint login = new Endpoint(new Limit(10, Duration.ofMinutes(15)), new Limit(30, Duration.ofMinutes(15)));
  private Endpoint register = new Endpoint(new Limit(5, Duration.ofHours(1)), new Limit(10, Duration.ofHours(1)));

  public static class Limit {

    private int capacity;
    private Duration window;

    public Limit() {}

    public Limit(int capacity, Duration window) {
      this.capacity = capacity;
      this.window = window;
    }

    public int getCapacity() {
      return capacity;
    }

    public void setCapacity(int capacity) {
      this.capacity = capacity;
    }

    public Duration getWindow() {
      return window;
    }

    public void setWindow(Duration window) {
      this.window = window;
    }
  }

  public static class Endpoint {

    /** Per account or per identifier being targeted. */
    private Limit identifier;

    /** Per client address. */
    private Limit origin;

    public Endpoint() {}

    public Endpoint(Limit identifier, Limit origin) {
      this.identifier = identifier;
      this.origin = origin;
    }

    public Limit getIdentifier() {
      return identifier;
    }

    public void setIdentifier(Limit identifier) {
      this.identifier = identifier;
    }

    public Limit getOrigin() {
      return origin;
    }

    public void setOrigin(Limit origin) {
      this.origin = origin;
    }
  }

  public RateLimitPolicy policyFor(RateLimitScope scope) {
    return switch (scope) {
      case LOGIN_ACCOUNT -> policy(scope, "login-account", login.getIdentifier());
      case LOGIN_ORIGIN -> policy(scope, "login-origin", login.getOrigin());
      case REGISTER_IDENTIFIER -> policy(scope, "register-identifier", register.getIdentifier());
      case REGISTER_ORIGIN -> policy(scope, "register-origin", register.getOrigin());
    };
  }

  private RateLimitPolicy policy(RateLimitScope scope, String policyId, Limit limit) {
    return new RateLimitPolicy(scope, policyId, limit.getCapacity(), limit.getWindow());
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public List<String> getTrustedProxies() {
    return trustedProxies;
  }

  public void setTrustedProxies(List<String> trustedProxies) {
    this.trustedProxies = trustedProxies;
  }

  public int getMaxTrackedKeys() {
    return maxTrackedKeys;
  }

  public void setMaxTrackedKeys(int maxTrackedKeys) {
    this.maxTrackedKeys = maxTrackedKeys;
  }

  public Endpoint getLogin() {
    return login;
  }

  public void setLogin(Endpoint login) {
    this.login = login;
  }

  public Endpoint getRegister() {
    return register;
  }

  public void setRegister(Endpoint register) {
    this.register = register;
  }
}
