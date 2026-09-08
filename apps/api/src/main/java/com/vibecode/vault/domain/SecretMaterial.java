package com.vibecode.vault.domain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Plaintext secret material, held for as short a time as possible.
 *
 * <p>Wrapping the bytes rather than passing a {@code String} buys three specific things: {@link
 * #toString()} cannot leak the value into a log or an exception, equality is by identity so a
 * collection cannot hash the content, and {@link #close()} overwrites the array.
 *
 * <p><b>What this does not promise.</b> It is not "zero memory exposure", and claiming that would be
 * dishonest. The value arrives as a JSON string, so the JVM has already made copies the garbage
 * collector owns and this class cannot reach — in the parser, in the request buffer, possibly in the
 * string pool. What is achieved is a much shorter lifetime for the copy this code controls, and the
 * removal of the easy ways a secret escapes: printing it, logging it, or holding it in a field that
 * outlives the request.
 */
public final class SecretMaterial implements AutoCloseable {

  private final byte[] value;
  private boolean cleared;

  private SecretMaterial(byte[] value) {
    this.value = value;
  }

  public static SecretMaterial of(byte[] raw) {
    if (raw == null || raw.length == 0) {
      throw new IllegalArgumentException("Secret material cannot be empty");
    }
    return new SecretMaterial(Arrays.copyOf(raw, raw.length));
  }

  /**
   * Builds material from characters, without going through {@code String}.
   *
   * <p>The caller's array is overwritten once encoded, so a credential read from a request does not
   * survive in two places.
   */
  public static SecretMaterial ofChars(char[] characters) {
    if (characters == null || characters.length == 0) {
      throw new IllegalArgumentException("Secret material cannot be empty");
    }
    ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(characters));
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    // Overwrite the intermediate buffer as well as the caller's array.
    if (encoded.hasArray()) {
      Arrays.fill(encoded.array(), (byte) 0);
    }
    Arrays.fill(characters, '\0');
    return new SecretMaterial(bytes);
  }

  /** A copy for the cipher. Callers must not retain it. */
  public byte[] bytes() {
    requireUsable();
    return Arrays.copyOf(value, value.length);
  }

  public int length() {
    requireUsable();
    return value.length;
  }

  private void requireUsable() {
    if (cleared) {
      throw new IllegalStateException("This secret material has already been cleared");
    }
  }

  /** Overwrites the held bytes. Idempotent, so try-with-resources is always safe. */
  @Override
  public void close() {
    Arrays.fill(value, (byte) 0);
    cleared = true;
  }

  /** Never the value. This is the whole reason the type exists. */
  @Override
  public String toString() {
    return "SecretMaterial[redacted, " + (cleared ? "cleared" : value.length + " bytes") + "]";
  }

  /** Identity equality on purpose: content-based equality invites hashing the secret. */
  @Override
  public boolean equals(Object other) {
    return this == other;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }
}
