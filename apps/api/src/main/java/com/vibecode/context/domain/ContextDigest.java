package com.vibecode.context.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256, hex-encoded. The platform's implementation, called in one place.
 *
 * <p>No algorithm is implemented here and none ever should be. This class exists so that the two
 * digests this module computes — the per-pack content fingerprint and the compiler's pack digest —
 * cannot drift into two different encodings of the same idea.
 *
 * <p>{@link ContextPack#contentFingerprint()} deliberately still carries its own copy of this call
 * rather than being rewired through here. Routing it through a new class could only change its
 * output by accident, and every fingerprint already written to the database would then disagree
 * with the code that claims to produce it. The duplication is two lines and is the cheaper risk.
 */
public final class ContextDigest {

  private ContextDigest() {}

  /** The SHA-256 of the UTF-8 bytes of {@code canonical}, as 64 lowercase hex characters. */
  public static String sha256Hex(String canonical) {
    if (canonical == null) {
      throw new IllegalArgumentException("Nothing to digest");
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return out.toString();
    } catch (NoSuchAlgorithmException impossible) {
      // SHA-256 is required of every Java platform; this branch cannot be reached in practice.
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
