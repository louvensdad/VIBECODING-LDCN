package com.vibecode.output.infrastructure;

import com.vibecode.output.domain.OutputAnalysisRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutputAnalysisRecordRepository extends JpaRepository<OutputAnalysisRecord, UUID> {

  Optional<OutputAnalysisRecord> findByEvidenceId(UUID evidenceId);

  List<OutputAnalysisRecord> findByEvidenceIdIn(List<UUID> evidenceIds);

  /**
   * Every verdict on this project's evidence that the analyzer said still needs fixing.
   *
   * <p>Deliberately unbounded. A run flagged {@code requiresCorrection} is by definition an open
   * problem, and how many a project has is a fact about the project rather than about how fast a
   * machine produces output — capping the answer would make an old, unresolved failure disappear
   * from the context engine entirely once enough newer evidence arrived.
   *
   * <p>Written as JPQL rather than derived from the method name because {@code
   * OutputAnalysisRecord} has no project column: the project is reachable only through the evidence
   * row the verdict refers to. Filtering in the database keeps the result the size of the answer
   * instead of the size of the evidence trail.
   *
   * <p>Ordered so that repeated reads of unchanged data return the same sequence; {@code id} makes
   * the order total when two verdicts share a creation instant.
   */
  @Query(
      """
      select analysis from OutputAnalysisRecord analysis
      where analysis.requiresCorrection = true
        and analysis.evidenceId in (
          select evidence.id from TaskEvidence evidence where evidence.projectId = :projectId)
      order by analysis.createdAt desc, analysis.id desc
      """)
  List<OutputAnalysisRecord> findRequiringCorrectionByProjectId(@Param("projectId") UUID projectId);
}
