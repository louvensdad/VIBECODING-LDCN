package com.vibecode.context.domain;

/**
 * The version of the policy that compiled a pack, stored with the pack.
 *
 * <p>It exists so a future reader can explain why an old pack differs from one compiled today
 * without guessing. Rules get added and removed; without a version stamped on the pack, the only
 * way to account for a difference between two packs is to assume the inputs changed, which is
 * usually wrong and always unprovable.
 *
 * <p><b>Deliberately not the application version.</b> Tying it to the build would change the stamp
 * on every release whether or not a single rule moved, and would leave two packs compiled by
 * identical rules claiming to have been compiled by different policies. The version moves when the
 * rules move, and only then — which means whoever changes a rule must also change this, on purpose.
 *
 * <p>It is part of the pack digest. Two packs holding identical content under different policies
 * are not the same pack: the same items admitted by different rules is exactly the difference a
 * reader needs to see.
 *
 * @param value a short opaque label, compared for equality and never parsed or ordered
 */
public record ContextPolicyVersion(String value) {

  /**
   * The policy in force. Bumped when a rule is added, removed or changed in what it admits.
   *
   * <p>A plain "1" rather than a date or a semantic triple: nothing compares two versions for
   * ordering, and a format that invites comparison would eventually get one.
   */
  public static final ContextPolicyVersion CURRENT = new ContextPolicyVersion("1");

  public ContextPolicyVersion {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("A policy version must have a value");
    }
    if (value.length() > 20) {
      throw new IllegalArgumentException(
          "A policy version is a short label, not a description: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
