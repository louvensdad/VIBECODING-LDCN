package com.vibecode.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.guardian.domain.ProjectSecurityAssessment;
import com.vibecode.guardian.domain.SecurityCategory;
import com.vibecode.guardian.domain.SecurityFinding;
import com.vibecode.guardian.domain.SecurityFindingStatus;
import com.vibecode.guardian.domain.SecurityGateStatus;
import com.vibecode.guardian.domain.SecuritySeverity;
import com.vibecode.guardian.domain.SecuritySourceType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecurityScoreAndGateTest {

  private final UUID projectId = UUID.randomUUID();

  private SecurityFinding createFinding(SecuritySeverity severity, SecurityFindingStatus status) {
    SecurityFinding f =
        new SecurityFinding(
            UUID.randomUUID(),
            projectId,
            SecuritySourceType.TASK_EVIDENCE,
            "task-1",
            SecurityCategory.SECRET_EXPOSURE,
            severity,
            "Finding " + severity,
            "Description",
            "[REDACTED]",
            "location",
            "Recommendation",
            "SEC-001",
            UUID.randomUUID().toString());
    if (status == SecurityFindingStatus.ACKNOWLEDGED) {
      f.acknowledge();
    } else if (status == SecurityFindingStatus.RESOLVED) {
      f.resolve("Resolved by test");
    }
    return f;
  }

  @Test
  @DisplayName("Clean project has score 100 and PASS gate")
  void cleanProject() {
    ProjectSecurityAssessment assessment =
        ProjectSecurityAssessment.calculate(projectId, List.of());

    assertThat(assessment.score()).isEqualTo(100);
    assertThat(assessment.gateStatus()).isEqualTo(SecurityGateStatus.PASS);
    assertThat(assessment.canProceed()).isTrue();
  }

  @Test
  @DisplayName("CRITICAL open finding sets gate to BLOCKED and deducts 35")
  void criticalFinding() {
    SecurityFinding critical = createFinding(SecuritySeverity.CRITICAL, SecurityFindingStatus.OPEN);
    ProjectSecurityAssessment assessment =
        ProjectSecurityAssessment.calculate(projectId, List.of(critical));

    assertThat(assessment.score()).isEqualTo(65);
    assertThat(assessment.gateStatus()).isEqualTo(SecurityGateStatus.BLOCKED);
    assertThat(assessment.canProceed()).isFalse();
    assertThat(assessment.critical()).isEqualTo(1);
  }

  @Test
  @DisplayName("HIGH open finding sets gate to REQUIRES_APPROVAL and deducts 15")
  void highFinding() {
    SecurityFinding high = createFinding(SecuritySeverity.HIGH, SecurityFindingStatus.OPEN);
    ProjectSecurityAssessment assessment =
        ProjectSecurityAssessment.calculate(projectId, List.of(high));

    assertThat(assessment.score()).isEqualTo(85);
    assertThat(assessment.gateStatus()).isEqualTo(SecurityGateStatus.REQUIRES_APPROVAL);
    assertThat(assessment.canProceed()).isFalse();
  }

  @Test
  @DisplayName("MEDIUM open finding sets gate to WARNING and deducts 7")
  void mediumFinding() {
    SecurityFinding medium = createFinding(SecuritySeverity.MEDIUM, SecurityFindingStatus.OPEN);
    ProjectSecurityAssessment assessment =
        ProjectSecurityAssessment.calculate(projectId, List.of(medium));

    assertThat(assessment.score()).isEqualTo(93);
    assertThat(assessment.gateStatus()).isEqualTo(SecurityGateStatus.WARNING);
    assertThat(assessment.canProceed()).isTrue();
  }

  @Test
  @DisplayName("Resolved findings do not penalize score or block gate")
  void resolvedFindings() {
    SecurityFinding resolved = createFinding(SecuritySeverity.CRITICAL, SecurityFindingStatus.RESOLVED);
    ProjectSecurityAssessment assessment =
        ProjectSecurityAssessment.calculate(projectId, List.of(resolved));

    assertThat(assessment.score()).isEqualTo(100);
    assertThat(assessment.gateStatus()).isEqualTo(SecurityGateStatus.PASS);
    assertThat(assessment.canProceed()).isTrue();
    assertThat(assessment.openFindings()).isEqualTo(0);
  }

  @Test
  @DisplayName("CRITICAL findings cannot have risk accepted")
  void criticalCannotAcceptRisk() {
    SecurityFinding critical = createFinding(SecuritySeverity.CRITICAL, SecurityFindingStatus.OPEN);
    assertThatThrownBy(() -> critical.acceptRisk("Trying to accept critical risk"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CRITICAL findings cannot have risk accepted");
  }

  @Test
  @DisplayName("HIGH findings can have risk accepted with a reason")
  void highCanAcceptRisk() {
    SecurityFinding high = createFinding(SecuritySeverity.HIGH, SecurityFindingStatus.OPEN);
    high.acceptRisk("Accepted for staging environment testing");
    assertThat(high.getStatus()).isEqualTo(SecurityFindingStatus.ACCEPTED_RISK);
  }
}

