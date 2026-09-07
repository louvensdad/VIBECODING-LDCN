package com.vibecode.guardian.domain;

/** Lifecycle of an observed security finding. */
public enum SecurityFindingStatus {
  OPEN,
  ACKNOWLEDGED,
  RESOLVED,
  FALSE_POSITIVE,
  ACCEPTED_RISK;

  public boolean isOpen() {
    return this == OPEN;
  }

  public boolean isActive() {
    return this == OPEN || this == ACKNOWLEDGED;
  }
}
