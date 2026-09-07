package com.vibecode.guardian.domain;

import java.util.Objects;

/** A candidate finding emitted by a single security rule. */
public record SecurityFindingCandidate(
    RuleId ruleId,
    SecurityCategory category,
    SecuritySeverity severity,
    String title,
    String description,
    String rawEvidence,
    String location,
    String recommendation) {

  public SecurityFindingCandidate {
    Objects.requireNonNull(ruleId, "ruleId cannot be null");
    Objects.requireNonNull(category, "category cannot be null");
    Objects.requireNonNull(severity, "severity cannot be null");
    Objects.requireNonNull(title, "title cannot be null");
    Objects.requireNonNull(description, "description cannot be null");
    rawEvidence = rawEvidence == null ? "" : rawEvidence;
    location = location == null ? "unspecified" : location;
    Objects.requireNonNull(recommendation, "recommendation cannot be null");
  }
}
