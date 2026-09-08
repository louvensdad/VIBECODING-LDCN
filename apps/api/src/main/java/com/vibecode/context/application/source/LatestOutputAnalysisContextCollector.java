package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.domain.TaskEvidence;
import com.vibecode.output.infrastructure.OutputAnalysisRecordRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stored verdict on each piece of recent evidence.
 *
 * <p>The analysis is a separate candidate from the evidence it judges, not a summary that replaces
 * it. A verdict without its evidence is an assertion nobody can check, and evidence without its
 * verdict is output nobody has read; which of the two a budget can afford is a decision for the
 * selection step, and it needs both on the table to make it.
 *
 * <p>Analyses are reached through the evidence rows of the project, which are themselves read under
 * the project's ownership check and bounded by the same {@link ContextReadWindow}. The analysis
 * table has no project column of its own, so this is also what keeps the read inside one project:
 * an evidence id the caller could not see produces no analysis here.
 *
 * <p>Put plainly, since this collector queries {@code OutputAnalysisRecordRepository} directly: the
 * ownership check it depends on is the {@code requireReadable} inside {@code
 * EvidenceService.listRecentForProject}, called first in {@link #collect}. The repository is asked
 * only about ids that read returned, so it is never given an id the caller has not already been
 * cleared for.
 *
 * <p>Every verdict in the window is emitted, failing and passing alike. Keeping only the failures
 * would quietly assert that a successful run says nothing worth knowing.
 */
@Component
@Transactional(readOnly = true)
public class LatestOutputAnalysisContextCollector implements ContextCollector {

  private final EvidenceService evidence;
  private final OutputAnalysisRecordRepository analyses;

  public LatestOutputAnalysisContextCollector(
      EvidenceService evidence, OutputAnalysisRecordRepository analyses) {
    this.evidence = evidence;
    this.analyses = analyses;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.LATEST_OUTPUT_ANALYSIS;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // listRecentForProject authorizes the project; the ids that come back are the only ones this
    // collector will ever ask the analysis table about.
    List<UUID> evidenceIds =
        evidence.listRecentForProject(projectId, window.recentRecords()).stream()
            .map(TaskEvidence::getId)
            .toList();
    if (evidenceIds.isEmpty()) {
      return List.of();
    }

    return analyses.findByEvidenceIdIn(evidenceIds).stream()
        // findByEvidenceIdIn imposes no order. Newest first, ties broken by id, so two collections
        // of unchanged data cannot disagree about the sequence.
        .sorted(
            Comparator.comparing(OutputAnalysisRecord::getCreatedAt)
                .reversed()
                .thenComparing(analysis -> analysis.getId().toString()))
        .map(analysis -> toCandidate(projectId, analysis))
        .toList();
  }

  private ContextItem toCandidate(UUID projectId, OutputAnalysisRecord analysis) {
    String signals =
        analysis.getSignalNames().isEmpty()
            ? "none recorded"
            : String.join(", ", analysis.getSignalNames());
    String content =
        analysis.getSummary()
            + "\nVerdict: "
            + analysis.getStatus()
            + "\nSignals: "
            + signals
            + "\nRequires correction: "
            + (analysis.isRequiresCorrection() ? "yes" : "no");

    ContextSource source =
        ContextSource.of(ContextSourceType.LATEST_OUTPUT_ANALYSIS, analysis.getId().toString());
    return new ContextItem(
        "analysis:" + analysis.getId(),
        ContextKind.EVIDENCE,
        "Output analysis: " + analysis.getStatus(),
        content,
        new ContextProvenance(source, projectId, analysis.getCreatedAt()));
  }
}
