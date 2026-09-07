package com.vibecode.prompt.application;

import com.vibecode.audit.application.AuditService;
import com.vibecode.audit.domain.AuditEventType;
import com.vibecode.guardian.application.SecurityAssessmentService;
import com.vibecode.guardian.application.SecurityRuleRegistry;
import com.vibecode.guardian.domain.ProjectSecurityAssessment;
import com.vibecode.guardian.domain.SecurityFinding;
import com.vibecode.guardian.domain.SecurityFindingCandidate;
import com.vibecode.guardian.domain.SecurityInspectionContext;
import com.vibecode.guardian.domain.SecurityRule;
import com.vibecode.guardian.domain.SecuritySeverity;
import com.vibecode.guardian.domain.SecuritySourceType;
import com.vibecode.guardian.domain.SensitiveDataRedactor;
import com.vibecode.guardian.infrastructure.SecurityFindingRepository;
import com.vibecode.prompt.domain.PromptSecurityStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Deterministically inspects generated prompts before they leave the platform toward external
 * models.
 *
 * <p>Two things happen here, and the order matters. The prompt text is redacted, so the content
 * handed back to the client never carries a raw secret even when copying is refused — a blocked
 * prompt that still displayed the credential would have leaked it into the UI anyway. Then, when
 * the project has open findings, the prompt is prefixed with what they are, so a refusal tells the
 * user what to fix instead of just saying no.
 */
@Component
public class PromptSecurityInspector {

  private final SecurityRuleRegistry registry;
  private final AuditService audit;
  private final SecurityAssessmentService assessmentService;
  private final SecurityFindingRepository findings;

  public PromptSecurityInspector(
      SecurityRuleRegistry registry,
      AuditService audit,
      @Lazy SecurityAssessmentService assessmentService,
      SecurityFindingRepository findings) {
    this.registry = registry;
    this.audit = audit;
    this.assessmentService = assessmentService;
    this.findings = findings;
  }

  public record PromptInspectionResult(
      PromptSecurityStatus status,
      boolean copyAllowed,
      String safeContent,
      List<String> findings) {}

  public PromptInspectionResult inspect(UUID projectId, UUID taskId, String rawPromptContent) {
    SecurityInspectionContext context =
        new SecurityInspectionContext(
            projectId,
            null,
            SecuritySourceType.PROMPT,
            taskId != null ? taskId.toString() : "prompt",
            rawPromptContent,
            Optional.empty(),
            Optional.empty(),
            Map.of());

    List<SecurityFindingCandidate> candidates = new ArrayList<>();
    for (SecurityRule rule : registry.rulesFor(context)) {
      candidates.addAll(rule.inspect(context));
    }

    boolean hasCriticalOrHigh =
        candidates.stream().anyMatch(candidate -> isCriticalOrHigh(candidate.severity()));
    boolean hasMediumOrLow =
        candidates.stream().anyMatch(candidate -> !isCriticalOrHigh(candidate.severity()));

    List<String> summaries =
        new ArrayList<>(
            candidates.stream()
                .map(c -> c.title() + " (" + c.severity() + "): " + c.description())
                .toList());

    // Redaction happens before anything is returned, never after.
    String safeContent = SensitiveDataRedactor.redact(rawPromptContent);

    // A prompt is also blocked by problems already recorded elsewhere in the project, not only by
    // what its own text happens to contain.
    List<SecurityFinding> blockingFindings = openCriticalOrHighFindings(projectId);
    ProjectSecurityAssessment assessment = assessmentService.assess(projectId);
    if (assessment.critical() > 0) {
      hasCriticalOrHigh = true;
      summaries.add(
          "O projeto possui "
              + assessment.critical()
              + " problema(s) crítico(s) de segurança aberto(s).");
    }

    if (hasCriticalOrHigh) {
      safeContent = withSecurityNotice(safeContent, blockingFindings, true);
      audit.record(
          projectId,
          AuditEventType.PROMPT_BLOCKED,
          "PROMPT",
          taskId != null ? taskId.toString() : "prompt",
          "BLOCKED",
          "Prompt bloqueado: credencial detectada ou projeto com vulnerabilidade crítica aberta.");
      return new PromptInspectionResult(
          PromptSecurityStatus.BLOCKED, false, safeContent, List.copyOf(summaries));
    }

    if (hasMediumOrLow || !blockingFindings.isEmpty()) {
      safeContent = withSecurityNotice(safeContent, blockingFindings, false);
      return new PromptInspectionResult(
          PromptSecurityStatus.WARNING, true, safeContent, List.copyOf(summaries));
    }

    return new PromptInspectionResult(PromptSecurityStatus.SAFE, true, safeContent, List.of());
  }

  private boolean isCriticalOrHigh(SecuritySeverity severity) {
    return severity == SecuritySeverity.CRITICAL || severity == SecuritySeverity.HIGH;
  }

  private List<SecurityFinding> openCriticalOrHighFindings(UUID projectId) {
    return findings.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
        .filter(finding -> finding.getStatus().isActive())
        .filter(finding -> isCriticalOrHigh(finding.getSeverity()))
        .toList();
  }

  /**
   * Puts the open findings at the top of the prompt.
   *
   * <p>The evidence quoted here is the finding's stored evidence, which was already redacted before
   * it reached the database — so this section explains the problem without reproducing the secret.
   */
  private String withSecurityNotice(
      String content, List<SecurityFinding> blockingFindings, boolean blocked) {
    if (blockingFindings.isEmpty()) {
      return content;
    }

    StringBuilder notice = new StringBuilder();
    notice.append(
        blocked
            ? "SEGURANÇA — ESTE PROMPT ESTÁ BLOQUEADO\n\n"
                + "Não copie nada daqui para um modelo externo enquanto os problemas abaixo\n"
                + "estiverem abertos. Corrija-os primeiro; a evidência já está redigida.\n"
            : "SEGURANÇA — ATENÇÃO\n\n"
                + "Há problemas de segurança abertos neste projeto. A evidência já está redigida.\n");

    for (SecurityFinding finding : blockingFindings) {
      notice
          .append("\n- [")
          .append(finding.getSeverity())
          .append("] ")
          .append(finding.getTitle())
          .append("\n  Onde: ")
          .append(finding.getLocation())
          .append("\n  Evidência: ")
          .append(finding.getEvidence())
          .append("\n  Ação: ")
          .append(finding.getRecommendation())
          .append('\n');
    }

    notice.append("\n---\n\n");
    return notice + content;
  }
}
