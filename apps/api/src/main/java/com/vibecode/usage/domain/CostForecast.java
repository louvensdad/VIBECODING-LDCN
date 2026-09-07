package com.vibecode.usage.domain;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * A projection of spend and remaining runway — the data behind the future "battery" indicator.
 *
 * <p>Always an estimate. {@code basedOnEvents} says how much evidence it rests on, so a forecast
 * built from three calls is not presented with the same weight as one built from three hundred.
 */
public record CostForecast(
    BigDecimal estimatedDailySpend,
    BigDecimal estimatedRemainingCredit,
    Duration estimatedAutonomy,
    int basedOnEvents,
    CreditConfidence sourceConfidence) {

  /** A forecast from an unknown balance cannot state runway, only burn rate. */
  public boolean canEstimateAutonomy() {
    return sourceConfidence != CreditConfidence.UNKNOWN && estimatedAutonomy != null;
  }
}
