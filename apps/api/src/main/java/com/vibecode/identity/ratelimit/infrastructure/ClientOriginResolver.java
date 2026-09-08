package com.vibecode.identity.ratelimit.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides which address a request is counted against.
 *
 * <p>Forwarded headers are ignored unless the immediate peer is a configured trusted proxy, and the
 * default trusted list is empty. That default is not caution for its own sake — it was measured.
 * The Next.js rewrite this app runs behind adds no {@code X-Forwarded-For} of its own but passes
 * through whatever the browser sent, so a client can put any value it likes in that header. Trusting
 * it because the peer happens to be the proxy would hand every visitor an unlimited supply of
 * distinct origin buckets and quietly disable the limiter.
 *
 * <p>Consequences of the default, stated plainly:
 *
 * <ul>
 *   <li><b>Local development, through the proxy</b> — every request arrives from the Next.js
 *       process, so all users share one origin bucket. The limiter is stricter than intended, not
 *       weaker, which is the right direction to fail in.
 *   <li><b>Production behind a real proxy</b> — the operator lists that proxy in
 *       {@code trusted-proxies}. This is only safe with a proxy that <em>overwrites</em>
 *       {@code X-Forwarded-For} with the connecting address (nginx: {@code proxy_set_header
 *       X-Forwarded-For $remote_addr}). A proxy that appends to a client-supplied value, or passes
 *       it through as the Next.js rewrite does, must never be listed.
 * </ul>
 */
@Component
public class ClientOriginResolver {

  private static final String FORWARDED_FOR = "X-Forwarded-For";
  private static final String UNKNOWN = "unknown";

  private final Set<String> trustedProxies;

  public ClientOriginResolver(AuthRateLimitProperties properties) {
    this.trustedProxies = Set.copyOf(properties.getTrustedProxies());
  }

  /**
   * The address to count against. Never returns null: an unresolvable peer shares a single bucket
   * rather than escaping the limit.
   */
  public String resolve(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    if (peer == null || peer.isBlank()) {
      return UNKNOWN;
    }
    if (!trustedProxies.contains(peer)) {
      return peer;
    }
    return firstForwardedAddress(request).orElse(peer);
  }

  /**
   * The left-most entry of {@code X-Forwarded-For}, which a trusted proxy sets to the address it
   * accepted the connection from.
   */
  private java.util.Optional<String> firstForwardedAddress(HttpServletRequest request) {
    String header = request.getHeader(FORWARDED_FOR);
    if (header == null || header.isBlank()) {
      return java.util.Optional.empty();
    }
    List<String> hops = List.of(header.split(","));
    return hops.stream().map(String::trim).filter(hop -> !hop.isBlank()).findFirst();
  }

  /** True when forwarded headers are honoured at all. Used by the startup report. */
  public boolean trustsAnyProxy() {
    return !trustedProxies.isEmpty();
  }
}
