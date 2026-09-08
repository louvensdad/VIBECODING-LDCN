package com.vibecode.identity.ratelimit.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Turns an email into an opaque bucket key.
 *
 * <p>The limiter needs to count attempts per account; it does not need to know which account. A
 * plain digest would not be enough — the space of email addresses is small enough to walk, so a
 * heap dump would become a list of everyone who has been trying to sign in. A random salt,
 * generated once per process, makes that offline reversal useless.
 *
 * <p>Limitations, deliberately accepted at this stage:
 *
 * <ul>
 *   <li>The salt lives only in memory and changes on restart, so buckets do not survive a restart.
 *       For an abuse counter measured in minutes that is fine, and it avoids introducing a managed
 *       secret before there is anywhere proper to keep one.
 *   <li>It is therefore per-instance, like the store itself.
 * </ul>
 *
 * <p>The fingerprint is derived from the normalized address, so {@code Alice@Example.com} and
 * {@code alice@example.com} share one bucket and casing cannot be used to get a fresh allowance.
 */
@Component
public class IdentifierFingerprint {

  private final byte[] salt = new byte[32];

  public IdentifierFingerprint() {
    new SecureRandom().nextBytes(salt);
  }

  /**
   * @param rawIdentifier whatever the caller typed; no attempt is made to validate it, because a
   *     malformed address must still consume its attempt rather than bypass the limit
   */
  public String of(String rawIdentifier) {
    String normalized =
        rawIdentifier == null ? "" : rawIdentifier.trim().toLowerCase(Locale.ROOT);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(salt);
      byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
      // Half the digest is far more than enough to keep collisions out of sight, and keeps the key
      // small.
      return HexFormat.of().formatHex(hash, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
