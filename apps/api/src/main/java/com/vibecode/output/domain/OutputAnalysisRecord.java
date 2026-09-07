package com.vibecode.output.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The stored verdict on one piece of evidence.
 *
 * <p>The technical signals are kept, not just the summary. A summary says "the build failed"; the
 * signals say it was a compilation error rather than a failing test, and that is what a correction
 * prompt needs to be about the right problem.
 *
 * <p>One record per evidence, created with it and never rewritten — re-analysing means submitting
 * new evidence.
 */
@Entity
@Table(name = "output_analysis_records")
public class OutputAnalysisRecord {

  private static final String SEPARATOR = ",";

  @Id private UUID id;

  @Column(name = "evidence_id", nullable = false, unique = true)
  private UUID evidenceId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private OutputAnalysisStatus status;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String summary;

  /** Signal names joined by commas. A join table would add a query for a short, fixed list. */
  @Column(nullable = false, columnDefinition = "TEXT")
  private String signals;

  @Column(name = "should_continue", nullable = false)
  private boolean shouldContinue;

  @Column(name = "requires_correction", nullable = false)
  private boolean requiresCorrection;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** For JPA only. */
  protected OutputAnalysisRecord() {}

  public OutputAnalysisRecord(UUID evidenceId, OutputAnalysis analysis) {
    this.id = UUID.randomUUID();
    this.evidenceId = evidenceId;
    this.status = analysis.status();
    this.summary = analysis.summary();
    this.signals =
        analysis.signals().stream().map(Enum::name).collect(Collectors.joining(SEPARATOR));
    this.shouldContinue = analysis.shouldContinue();
    this.requiresCorrection = analysis.requiresCorrection();
    this.createdAt = Instant.now();
  }

  public List<OutputSignal> getSignals() {
    if (signals == null || signals.isBlank()) {
      return List.of();
    }
    return Arrays.stream(signals.split(SEPARATOR)).map(OutputSignal::valueOf).toList();
  }

  public List<String> getSignalNames() {
    return getSignals().stream().map(Enum::name).toList();
  }

  public UUID getId() {
    return id;
  }

  public UUID getEvidenceId() {
    return evidenceId;
  }

  public OutputAnalysisStatus getStatus() {
    return status;
  }

  public String getSummary() {
    return summary;
  }

  public boolean isShouldContinue() {
    return shouldContinue;
  }

  public boolean isRequiresCorrection() {
    return requiresCorrection;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
