package com.vibecode.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A normalized email address.
 *
 * <p>Normalization is deliberately conservative: trim surrounding whitespace and lowercase. That is
 * enough to stop the same address registering twice as {@code Ana@Example.com} and
 * {@code ana@example.com}.
 *
 * <p>What it does <em>not</em> do is strip dots or {@code +tags}. Those rules are specific to a few
 * providers; applying them everywhere would merge two genuinely different mailboxes into one
 * account, which is a far worse failure than allowing two aliases of the same inbox.
 */
public record EmailAddress(String value) {

  private static final int MAX_LENGTH = 320;

  /**
   * Pragmatic shape check: a local part, an at-sign, a dotted domain. Full RFC 5322 is not worth
   * implementing — the only real proof an address works is delivery to it.
   */
  private static final Pattern SHAPE =
      Pattern.compile("^[^\\s@]{1,64}@[^\\s@.]+(\\.[^\\s@.]+)+$");

  public EmailAddress {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Email is required");
    }
    value = value.trim().toLowerCase(Locale.ROOT);
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("Email is too long");
    }
    if (!SHAPE.matcher(value).matches()) {
      throw new IllegalArgumentException("Email format is invalid");
    }
  }

  public static EmailAddress of(String raw) {
    return new EmailAddress(raw);
  }

  @Override
  public String toString() {
    return value;
  }
}
