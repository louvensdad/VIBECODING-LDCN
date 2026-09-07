package com.vibecode.guardian.domain;

/** How critical a security finding is. */
public enum SecuritySeverity {
  INFO(0),
  LOW(2),
  MEDIUM(7),
  HIGH(15),
  CRITICAL(35);

  private final int scorePenalty;

  SecuritySeverity(int scorePenalty) {
    this.scorePenalty = scorePenalty;
  }

  public int scorePenalty() {
    return scorePenalty;
  }
}
