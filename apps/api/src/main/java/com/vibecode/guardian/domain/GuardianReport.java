package com.vibecode.guardian.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Everything one guardian found in a single pass. */
public record GuardianReport(
    UUID projectId, GuardianId guardian, List<GuardianFinding> findings, Instant generatedAt) {

  public GuardianReport {
    findings = List.copyOf(findings);
  }

  public boolean hasBlockingFindings() {
    return findings.stream()
        .anyMatch(
            finding ->
                finding.severity() == GuardianSeverity.CRITICAL
                    || finding.severity() == GuardianSeverity.HIGH);
  }
}
