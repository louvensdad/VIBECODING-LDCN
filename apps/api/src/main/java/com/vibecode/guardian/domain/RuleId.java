package com.vibecode.guardian.domain;

import java.util.Objects;

/** Identifies a registered deterministic security rule. */
public record RuleId(String value) {

  public RuleId {
    Objects.requireNonNull(value, "Rule id cannot be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("Rule id cannot be blank");
    }
  }

  public static RuleId of(String value) {
    return new RuleId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
