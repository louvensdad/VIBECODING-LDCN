package com.vibecode.guardian.web;

import com.vibecode.guardian.domain.ProjectSecurityAssessment;
import com.vibecode.guardian.domain.SecurityGateStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SecurityAssessmentResponse(
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

  public static SecurityAssessmentResponse from(ProjectSecurityAssessment a) {
    return new SecurityAssessmentResponse(
        a.projectId(),
        a.score(),
        a.openFindings(),
        a.critical(),
        a.high(),
        a.medium(),
        a.low(),
        a.gateStatus(),
        a.canProceed(),
        a.blockingReasons(),
        a.warnings(),
        a.evaluatedAt());
  }
}

