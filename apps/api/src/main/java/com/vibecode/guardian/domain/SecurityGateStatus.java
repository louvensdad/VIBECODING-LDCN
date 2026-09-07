package com.vibecode.guardian.domain;

import java.util.List;

/** Status of the safety gate evaluation. */
public enum SecurityGateStatus {
  PASS,
  WARNING,
  REQUIRES_APPROVAL,
  BLOCKED;

  public boolean canProceed() {
    return this == PASS || this == WARNING;
  }
}
