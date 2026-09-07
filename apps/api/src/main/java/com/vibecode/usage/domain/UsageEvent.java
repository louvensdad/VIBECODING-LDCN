package com.vibecode.usage.domain;

import com.vibecode.model.domain.ProviderId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One measured interaction with a provider.
 *
 * <p>Token counts are observed facts. {@code estimatedCost} is a local calculation from a price
 * table and is labelled as an estimate everywhere it surfaces.
 */
public record UsageEvent(
    UUID id,
    UUID projectId,
    ProviderId provider,
    String modelId,
    long inputTokens,
    long outputTokens,
    BigDecimal estimatedCost,
    Instant occurredAt) {

  public long totalTokens() {
    return inputTokens + outputTokens;
  }
}
