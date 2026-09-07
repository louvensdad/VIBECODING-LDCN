package com.vibecode.guardian.web;

import com.vibecode.guardian.application.SecurityAssessmentService;
import com.vibecode.guardian.application.SecurityGuardianService;
import com.vibecode.guardian.domain.ProjectSecurityAssessment;
import com.vibecode.guardian.domain.SecurityFinding;
import com.vibecode.guardian.domain.SecurityInspectionContext;
import com.vibecode.identity.domain.CurrentUser;
import com.vibecode.identity.domain.CurrentUserProvider;
import com.vibecode.project.application.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/security")
public class SecurityController {

  private final SecurityGuardianService guardian;
  private final SecurityAssessmentService assessment;
  private final ProjectService projects;
  private final CurrentUserProvider currentUserProvider;

  public SecurityController(
      SecurityGuardianService guardian,
      SecurityAssessmentService assessment,
      ProjectService projects,
      CurrentUserProvider currentUserProvider) {
    this.guardian = guardian;
    this.assessment = assessment;
    this.projects = projects;
    this.currentUserProvider = currentUserProvider;
  }

  @PostMapping("/inspect")
  @ResponseStatus(HttpStatus.OK)
  public List<SecurityFindingResponse> inspect(
      @PathVariable UUID projectId, @Valid @RequestBody InspectSecurityRequest request) {
    projects.requireWritable(projectId);
    UUID userId = currentUserProvider.current().map(CurrentUser::id).orElse(null);
    SecurityInspectionContext context =
        SecurityInspectionContext.forText(
            projectId, userId, request.sourceType(), request.sourceId(), request.content());

    List<SecurityFinding> findings = guardian.inspect(context);
    return findings.stream().map(SecurityFindingResponse::from).toList();
  }

  @GetMapping
  public SecurityAssessmentResponse getAssessment(@PathVariable UUID projectId) {
    ProjectSecurityAssessment result = assessment.assess(projectId);
    return SecurityAssessmentResponse.from(result);
  }

  @GetMapping("/findings")
  public List<SecurityFindingResponse> listFindings(@PathVariable UUID projectId) {
    return guardian.listForProject(projectId).stream()
        .map(SecurityFindingResponse::from)
        .toList();
  }

  @GetMapping("/findings/{findingId}")
  public SecurityFindingResponse getFinding(
      @PathVariable UUID projectId, @PathVariable UUID findingId) {
    return SecurityFindingResponse.from(guardian.require(projectId, findingId));
  }

  @PostMapping("/findings/{findingId}/acknowledge")
  public SecurityFindingResponse acknowledge(
      @PathVariable UUID projectId, @PathVariable UUID findingId) {
    return SecurityFindingResponse.from(guardian.acknowledge(projectId, findingId));
  }

  @PostMapping("/findings/{findingId}/resolve")
  public SecurityFindingResponse resolve(
      @PathVariable UUID projectId,
      @PathVariable UUID findingId,
      @RequestBody(required = false) FindingDecisionRequest request) {
    String reason = request != null && request.reason() != null ? request.reason() : "Remediado pelo usuário";
    return SecurityFindingResponse.from(guardian.resolve(projectId, findingId, reason));
  }

  @PostMapping("/findings/{findingId}/accept-risk")
  public SecurityFindingResponse acceptRisk(
      @PathVariable UUID projectId,
      @PathVariable UUID findingId,
      @RequestBody FindingDecisionRequest request) {
    String reason = request != null ? request.reason() : null;
    return SecurityFindingResponse.from(guardian.acceptRisk(projectId, findingId, reason));
  }

  @PostMapping("/findings/{findingId}/false-positive")
  public SecurityFindingResponse markFalsePositive(
      @PathVariable UUID projectId,
      @PathVariable UUID findingId,
      @RequestBody FindingDecisionRequest request) {
    String reason = request != null ? request.reason() : null;
    return SecurityFindingResponse.from(guardian.markFalsePositive(projectId, findingId, reason));
  }
}
