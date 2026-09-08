package com.vibecode.context.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256, hex-encoded. The platform's implementation, and nothing else.
 *
 * <p>No algorithm is implemented here and none ever should be.
 *
 * <p><b>It has one caller and it de-duplicates nothing.</b> {@link
 * ContextPack#contentFingerprint()} computes the same hash from its own copy of these two lines,
 * and that is deliberate rather than an oversight waiting to be tidied: rewiring it through here
 * could only change its output by accident, and every fingerprint already written to the database
 * would then disagree with the code that claims to produce it. So this is a home for the pack
 * digest, kept separate from the fingerprint on purpose.
 *
 * <p>Which means the obvious refactor - "fold contentFingerprint into this" - is the one thing not
 * to do. If the two ever must share an implementation, that is a migration with a decision about
 * every stored fingerprint attached to it, not a cleanup.
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
