package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.domain.TaskEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recorded evidence of work that actually ran: builds, test runs, terminal output, model answers.
 *
 * <p>This is the one source that grows without a ceiling, so it is read through {@link
 * ContextReadWindow}. The window bounds the query and nothing else: every row it returns becomes a
 * candidate, in the order the evidence trail already has. Nothing is ranked and no failure is
 * skipped because a later success followed it — the trail is worth having precisely because it still
 * shows the failure that preceded the fix.
 *
 * <p>Evidence content is stored already redacted by the guardian's pipeline at the moment it is
 * written, so a raw credential never reaches this row and therefore never reaches this collector.
 * Nothing here re-reads an unredacted original; there is none to read.
 */
@Component
@Transactional(readOnly = true)
public class LatestEvidenceContextCollector implements ContextCollector {

  private final EvidenceService evidence;

  public LatestEvidenceContextCollector(EvidenceService evidence) {
    this.evidence = evidence;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.LATEST_EVIDENCE;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // listRecentForProject authorizes the project itself, and its query already orders by
    // createdAt then id — a total order, so the window always cuts at the same place.
    return evidence.listRecentForProject(projectId, window.recentRecords()).stream()
        .map(record -> toCandidate(projectId, record))
        .toList();
  }

  private ContextItem toCandidate(UUID projectId, TaskEvidence record) {
    ContextSource source =
        ContextSource.of(ContextSourceType.LATEST_EVIDENCE, record.getId().toString());
    return new ContextItem(
        "evidence:" + record.getId(),
        ContextKind.EVIDENCE,
        record.getType() + " from " + record.getSource(),
        record.getRawContent(),
        new ContextProvenance(source, projectId, record.getCreatedAt()));
  }
}
