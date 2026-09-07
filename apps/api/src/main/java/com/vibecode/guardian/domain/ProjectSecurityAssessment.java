package com.vibecode.guardian.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The operational security assessment for a project based on known findings. */
public record ProjectSecurityAssessment(
    UUID projectId,
    int score,
    int openFindings,
    int critical,
    int high,
    int medium,
    int low,
    SecurityGateStatus gateStatus,
    boolean canProceed,
    List<String> blockingReasons,
    List<String> warnings,
    Instant evaluatedAt) {

  public ProjectSecurityAssessment {
    Objects.requireNonNull(projectId, "projectId cannot be null");
    Objects.requireNonNull(gateStatus, "gateStatus cannot be null");
    blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    evaluatedAt = evaluatedAt == null ? Instant.now() : evaluatedAt;
  }

  public static ProjectSecurityAssessment calculate(
      UUID projectId, List<SecurityFinding> allFindings) {
    List<SecurityFinding> open =
        allFindings.stream().filter(f -> f.getStatus().isOpen()).toList();

    int criticalCount =
        (int) open.stream().filter(f -> f.getSeverity() == SecuritySeverity.CRITICAL).count();
    int highCount =
        (int) open.stream().filter(f -> f.getSeverity() == SecuritySeverity.HIGH).count();
    int mediumCount =
        (int) open.stream().filter(f -> f.getSeverity() == SecuritySeverity.MEDIUM).count();
    int lowCount =
        (int) open.stream().filter(f -> f.getSeverity() == SecuritySeverity.LOW).count();

    // Base = 100, CRITICAL: -35, HIGH: -15, MEDIUM: -7, LOW: -2
    int penalty =
        (criticalCount * SecuritySeverity.CRITICAL.scorePenalty())
            + (highCount * SecuritySeverity.HIGH.scorePenalty())
            + (mediumCount * SecuritySeverity.MEDIUM.scorePenalty())
            + (lowCount * SecuritySeverity.LOW.scorePenalty());

    int score = Math.max(0, Math.min(100, 100 - penalty));

    SecurityGateResult gate = SecurityGateResult.evaluate(allFindings);

    return new ProjectSecurityAssessment(
        projectId,
        score,
        open.size(),
        criticalCount,
        highCount,
        mediumCount,
        lowCount,
        gate.status(),
        gate.canProceed(),
        gate.blockingReasons(),
        gate.warnings(),
        Instant.now());
  }
}

