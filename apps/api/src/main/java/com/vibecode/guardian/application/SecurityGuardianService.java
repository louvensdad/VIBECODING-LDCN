package com.vibecode.guardian.application;

import com.vibecode.audit.application.AuditService;
import com.vibecode.audit.domain.AuditEventType;
import com.vibecode.brain.application.MemoryProposalService;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.brain.domain.MemoryProposalTrigger;
import com.vibecode.guardian.domain.ProjectSecurityAssessment;
import com.vibecode.guardian.domain.SecurityFinding;
import com.vibecode.guardian.domain.SecurityFindingCandidate;
import com.vibecode.guardian.domain.SecurityGateStatus;
import com.vibecode.guardian.domain.SecurityInspectionContext;
import com.vibecode.guardian.domain.SecurityRule;
import com.vibecode.guardian.domain.SecuritySeverity;
import com.vibecode.guardian.domain.SensitiveDataRedactor;
import com.vibecode.guardian.infrastructure.SecurityFindingRepository;
import com.vibecode.project.application.ProjectService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deterministic Security Guardian engine.
 *
 * <p>Inspects content against registered rules, generates evidence-backed findings,
 * redacts secrets, manages finding lifecycles, and triggers audit events.
 */
@Service
@Transactional
public class SecurityGuardianService {

  public static final int MAX_INSPECTABLE_TEXT_SIZE = 100_000;

  private final SecurityRuleRegistry registry;
  private final SecurityFindingRepository findings;
  private final AuditService audit;
  private final MemoryProposalService memoryProposals;
  private final ProjectService projects;

  public SecurityGuardianService(
      SecurityRuleRegistry registry,
      SecurityFindingRepository findings,
      AuditService audit,
      MemoryProposalService memoryProposals,
      @Lazy ProjectService projects) {
    this.registry = registry;
    this.findings = findings;
    this.audit = audit;
    this.memoryProposals = memoryProposals;
    this.projects = projects;
  }

  public List<SecurityFinding> inspect(SecurityInspectionContext context) {
    if (context.rawContent() != null && context.rawContent().length() > MAX_INSPECTABLE_TEXT_SIZE) {
      throw new IllegalArgumentException(
          "O conteúdo excede o limite máximo inspecionável de " + MAX_INSPECTABLE_TEXT_SIZE + " caracteres.");
    }

    List<SecurityRule> activeRules = registry.rulesFor(context);
    List<SecurityFinding> result = new ArrayList<>();

    for (SecurityRule rule : activeRules) {
      List<SecurityFindingCandidate> candidates = rule.inspect(context);
      for (SecurityFindingCandidate candidate : candidates) {
        SecurityFinding finding = recordOrUpdateCandidate(context, candidate);
        result.add(finding);
      }
    }

    // Safety gate evaluation
    List<SecurityFinding> allProjectFindings =
        findings.findByProjectIdOrderByCreatedAtDesc(context.projectId());
    ProjectSecurityAssessment assessment =
        ProjectSecurityAssessment.calculate(context.projectId(), allProjectFindings);

    if (assessment.gateStatus() == SecurityGateStatus.BLOCKED) {
      audit.record(
          context.projectId(),
          AuditEventType.SECURITY_GATE_BLOCKED,
          "PROJECT_SECURITY_GATE",
          context.projectId().toString(),
          "BLOCKED",
          "O portão de segurança foi bloqueado devido a " + assessment.critical() + " problema(s) crítico(s).");
    }

    return List.copyOf(result);
  }

  private SecurityFinding recordOrUpdateCandidate(
      SecurityInspectionContext context, SecurityFindingCandidate candidate) {
    String redactedEvidence = SensitiveDataRedactor.redact(candidate.rawEvidence());
    String fingerprint =
        generateFingerprint(
            context.projectId(),
            candidate.ruleId().value(),
            context.sourceType().name(),
            candidate.location(),
            redactedEvidence);

    Optional<SecurityFinding> existing =
        findings.findByProjectIdAndFingerprint(context.projectId(), fingerprint);

    if (existing.isPresent()) {
      SecurityFinding found = existing.get();
      found.recordOccurrence(candidate.location(), redactedEvidence);
      return findings.save(found);
    }

    SecurityFinding newFinding =
        new SecurityFinding(
            UUID.randomUUID(),
            context.projectId(),
            context.sourceType(),
            context.sourceId(),
            candidate.category(),
            candidate.severity(),
            candidate.title(),
            candidate.description(),
            redactedEvidence,
            candidate.location(),
            candidate.recommendation(),
            candidate.ruleId().value(),
            fingerprint);

    SecurityFinding saved = findings.save(newFinding);

    // Audit event
    audit.record(
        context.projectId(),
        AuditEventType.SECURITY_FINDING_CREATED,
        "SECURITY_FINDING",
        saved.getId().toString(),
        "CREATED",
        "Severidade: " + saved.getSeverity() + " | Regra: " + saved.getRuleId());

    // Memory update proposal if critical or high
    if (saved.getSeverity() == SecuritySeverity.CRITICAL || saved.getSeverity() == SecuritySeverity.HIGH) {
      memoryProposals.propose(
          context.projectId(),
          MemoryProposalTrigger.ERROR_FOUND,
          BrainEntryType.ERROR,
          "Alerta de Segurança: " + saved.getTitle(),
          "Regra: " + saved.getRuleId() + " (" + saved.getSeverity() + ")\nRecomendação: " + saved.getRecommendation(),
          "Security Guardian (" + saved.getSourceType() + ")");
    }

    return saved;
  }

  public SecurityFinding acknowledge(UUID projectId, UUID findingId) {
    projects.requireWritable(projectId);
    SecurityFinding finding = require(projectId, findingId);
    finding.acknowledge();
    SecurityFinding saved = findings.save(finding);

    audit.record(
        projectId,
        AuditEventType.SECURITY_FINDING_ACKNOWLEDGED,
        "SECURITY_FINDING",
        saved.getId().toString(),
        "ACKNOWLEDGED",
        "Finding reconhecido pelo usuário.");

    return saved;
  }

  public SecurityFinding resolve(UUID projectId, UUID findingId, String reason) {
    projects.requireWritable(projectId);
    SecurityFinding finding = require(projectId, findingId);
    finding.resolve(reason);
    SecurityFinding saved = findings.save(finding);

    audit.record(
        projectId,
        AuditEventType.SECURITY_FINDING_RESOLVED,
        "SECURITY_FINDING",
        saved.getId().toString(),
        "RESOLVED",
        "Motivo da resolução: " + reason);

    return saved;
  }

  public SecurityFinding acceptRisk(UUID projectId, UUID findingId, String reason) {
    projects.requireWritable(projectId);
    SecurityFinding finding = require(projectId, findingId);
    finding.acceptRisk(reason);
    SecurityFinding saved = findings.save(finding);

    audit.record(
        projectId,
        AuditEventType.SECURITY_RISK_ACCEPTED,
        "SECURITY_FINDING",
        saved.getId().toString(),
        "ACCEPTED_RISK",
        "Risco aceito com justificativa: " + reason);

    return saved;
  }

  public SecurityFinding markFalsePositive(UUID projectId, UUID findingId, String reason) {
    projects.requireWritable(projectId);
    SecurityFinding finding = require(projectId, findingId);
    finding.markFalsePositive(reason);
    SecurityFinding saved = findings.save(finding);

    audit.record(
        projectId,
        AuditEventType.SECURITY_FINDING_RESOLVED,
        "SECURITY_FINDING",
        saved.getId().toString(),
        "FALSE_POSITIVE",
        "Marcado como falso positivo: " + reason);

    return saved;
  }

  @Transactional(readOnly = true)
  public List<SecurityFinding> listForProject(UUID projectId) {
    projects.requireReadable(projectId);
    return findings.findByProjectIdOrderByCreatedAtDesc(projectId);
  }

  @Transactional(readOnly = true)
  public SecurityFinding require(UUID projectId, UUID findingId) {
    projects.requireReadable(projectId);
    return findings
        .findByIdAndProjectId(findingId, projectId)
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    "Finding de segurança não encontrado: " + findingId));
  }

  private String generateFingerprint(
      UUID projectId,
      String ruleId,
      String sourceType,
      String location,
      String redactedSignature) {
    String input =
        projectId
            + ":"
            + ruleId
            + ":"
            + sourceType
            + ":"
            + location
            + ":"
            + redactedSignature;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Algoritmo SHA-256 indisponível", e);
    }
  }
}

