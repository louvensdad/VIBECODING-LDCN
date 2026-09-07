package com.vibecode.usage.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** A spending ceiling the user sets for a project. */
public record ProjectBudget(UUID projectId, BigDecimal limit, String currency) {

  public boolean isExceededBy(BigDecimal spend) {
    return spend.compareTo(limit) > 0;
  }
}
