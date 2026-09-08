package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.guardian.application.SecurityAssessmentService;
import com.vibecode.guardian.domain.ProjectSecurityAssessment;
import com.vibecode.guardian.domain.SecurityFinding;
import com.vibecode.guardian.infrastructure.SecurityFindingRepository;
import com.vibecode.project.application.ProjectService;
import com.vibecode.project.domain.Project;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The project's security posture, as a count and a verdict — never as a finding.
 *
 * <p><b>Nothing from inside a finding leaves this collector.</b> Not its title, not its description,
 * not its location, not its evidence, not the rule that produced it. What comes out is a score, a
 * gate status, and how many findings are open at each severity. Finding evidence is already redacted
 * by the guardian's pipeline before it is stored, but that is not the reason for this restraint: a
 * summary that carried finding text would be a way for security content to reach a prompt through a
 * source whose whole contract is that it does not, and the redaction upstream would then be the only
 * thing standing between a prompt and a secret. One layer is not a boundary.
 *
 * <p>The gate's blocking reasons and warnings are also withheld, for the same reason. They are
 * derived from the findings and would drift toward naming them; a count already tells a reader that
 * something is blocking, and the security screens are where the details belong.
 *
 * <p>Even a clean project produces an item. "Score 100, gate PASS, no open findings" is a fact worth
 * having: a missing item would be indistinguishable from a source nobody managed to read.
 *
 * <p><b>On the timestamp.</b> The assessment is recomputed on every call and stamps itself with the
 * moment of computation, which is the wrong instant for provenance — it would make the posture look
 * freshly observed on every collection and would make two collections of unchanged data differ. So
 * the item is dated at the most recent change among the findings the posture was computed from,
 * falling back to the project's own last change when there are none. The findings are read for their
 * timestamps alone; no field of theirs is ever put into an item.
 */
@Component
@Transactional(readOnly = true)
public class SecuritySummaryContextCollector implements ContextCollector {

  private final ProjectService projects;
  private final SecurityAssessmentService assessments;
  private final SecurityFindingRepository findings;

  public SecuritySummaryContextCollector(
      ProjectService projects,
      SecurityAssessmentService assessments,
      SecurityFindingRepository findings) {
    this.projects = projects;
    this.assessments = assessments;
    this.findings = findings;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.SECURITY_SUMMARY;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // requireReadable gates both reads below: the assessment service repeats the check for itself,
    // and the finding repository queries by project id and would answer anyone without this.
    Project project = projects.requireReadable(projectId);
    ProjectSecurityAssessment assessment = assessments.assess(projectId);

    String content =
        "Security score: "
            + assessment.score()
            + "/100"
            + "\nGate: "
            + assessment.gateStatus()
            + (assessment.canProceed() ? " (work may proceed)" : " (work is held)")
            + "\nOpen findings: "
            + assessment.openFindings()
            + "\nBy severity — critical: "
            + assessment.critical()
            + ", high: "
            + assessment.high()
            + ", medium: "
            + assessment.medium()
            + ", low: "
            + assessment.low();

    ContextSource source =
        ContextSource.of(ContextSourceType.SECURITY_SUMMARY, projectId.toString());
    return List.of(
        new ContextItem(
            "security-summary:" + projectId,
            ContextKind.SECURITY_NOTE,
            "Security posture",
            content,
            new ContextProvenance(source, projectId, observedAt(project))));
  }

  private Instant observedAt(Project project) {
    List<SecurityFinding> all = findings.findByProjectIdOrderByCreatedAtDesc(project.getId());
    Instant latest =
        all.stream().map(SecurityFinding::getUpdatedAt).max(Instant::compareTo).orElse(null);
    return SourceObservation.latestOf(project.getUpdatedAt(), latest);
  }
}
