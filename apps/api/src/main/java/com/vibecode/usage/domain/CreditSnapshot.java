package com.vibecode.usage.domain;

import com.vibecode.model.domain.ProviderId;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a provider account is believed to hold at a point in time.
 *
 * <p>An UNKNOWN snapshot carries no amount at all. Inventing a balance is worse than showing none,
 * because the user would plan against it.
 */
public record CreditSnapshot(
    ProviderId provider,
    String accountLabel,
    BigDecimal amount,
    String currency,
    CreditConfidence confidence,
    Instant observedAt) {

  public CreditSnapshot {
    if (confidence == CreditConfidence.UNKNOWN && amount != null) {
      throw new IllegalArgumentException("An UNKNOWN credit snapshot must not carry an amount");
    }
    if (confidence != CreditConfidence.UNKNOWN && amount == null) {
      throw new IllegalArgumentException("A known credit snapshot requires an amount");
    }
  }

  public static CreditSnapshot unknown(ProviderId provider, String accountLabel) {
    return new CreditSnapshot(
        provider, accountLabel, null, null, CreditConfidence.UNKNOWN, Instant.now());
  }

  /** True only for figures the provider itself reported. */
  public boolean isFact() {
    return confidence == CreditConfidence.EXACT;
  }
}
