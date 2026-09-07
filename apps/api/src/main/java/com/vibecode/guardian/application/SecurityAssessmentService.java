package com.vibecode.guardian.application;

import com.vibecode.guardian.domain.ProjectSecurityAssessment;
import com.vibecode.guardian.domain.SecurityFinding;
import com.vibecode.guardian.infrastructure.SecurityFindingRepository;
import com.vibecode.project.application.ProjectService;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Calculates operational security score and gate status for projects. */
@Service
@Transactional(readOnly = true)
public class SecurityAssessmentService {

  private final SecurityFindingRepository findings;
  private final ProjectService projects;

  public SecurityAssessmentService(
      SecurityFindingRepository findings, @Lazy ProjectService projects) {
    this.findings = findings;
    this.projects = projects;
  }

  public ProjectSecurityAssessment assess(UUID projectId) {
    projects.requireReadable(projectId);
    List<SecurityFinding> allFindings =
        findings.findByProjectIdOrderByCreatedAtDesc(projectId);
    return ProjectSecurityAssessment.calculate(projectId, allFindings);
  }
}

