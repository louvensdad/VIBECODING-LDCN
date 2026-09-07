package com.vibecode.guardian.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An evidence-backed record of a security risk discovered in a project. */
@Entity
@Table(name = "security_findings")
public class SecurityFinding {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 40)
  private SecuritySourceType sourceType;

  @Column(name = "source_id", nullable = false, length = 120)
  private String sourceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 60)
  private SecurityCategory category;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 20)
  private SecuritySeverity severity;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private SecurityFindingStatus status;

  @Column(name = "title", nullable = false, length = 200)
  private String title;

  @Column(name = "description", nullable = false, length = 1000)
  private String description;

  @Column(name = "evidence", nullable = false, columnDefinition = "TEXT")
  private String evidence;

  @Column(name = "location", nullable = false, length = 300)
  private String location;

  @Column(name = "recommendation", nullable = false, length = 1000)
  private String recommendation;

  @Column(name = "rule_id", nullable = false, length = 50)
  private String ruleId;

  @Column(name = "fingerprint", nullable = false, length = 128)
  private String fingerprint;

  @Column(name = "occurrence_count", nullable = false)
  private int occurrenceCount;

  @Column(name = "resolution_reason", length = 500)
  private String resolutionReason;

  @Column(name = "first_detected_at", nullable = false)
  private Instant firstDetectedAt;

  @Column(name = "last_detected_at", nullable = false)
  private Instant lastDetectedAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SecurityFinding() {}

  public SecurityFinding(
      UUID id,
      UUID projectId,
      SecuritySourceType sourceType,
      String sourceId,
      SecurityCategory category,
      SecuritySeverity severity,
      String title,
      String description,
      String evidence,
      String location,
      String recommendation,
      String ruleId,
      String fingerprint) {
    Instant now = Instant.now();
    this.id = Objects.requireNonNull(id, "id cannot be null");
    this.projectId = Objects.requireNonNull(projectId, "projectId cannot be null");
    this.sourceType = Objects.requireNonNull(sourceType, "sourceType cannot be null");
    this.sourceId = Objects.requireNonNull(sourceId, "sourceId cannot be null");
    this.category = Objects.requireNonNull(category, "category cannot be null");
    this.severity = Objects.requireNonNull(severity, "severity cannot be null");
    this.status = SecurityFindingStatus.OPEN;
    this.title = Objects.requireNonNull(title, "title cannot be null");
    this.description = Objects.requireNonNull(description, "description cannot be null");
    this.evidence = Objects.requireNonNull(evidence, "evidence cannot be null");
    this.location = Objects.requireNonNull(location, "location cannot be null");
    this.recommendation = Objects.requireNonNull(recommendation, "recommendation cannot be null");
    this.ruleId = Objects.requireNonNull(ruleId, "ruleId cannot be null");
    this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint cannot be null");
    this.occurrenceCount = 1;
    this.firstDetectedAt = now;
    this.lastDetectedAt = now;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void recordOccurrence(String newLocation, String newRedactedEvidence) {
    this.occurrenceCount++;
    this.lastDetectedAt = Instant.now();
    this.updatedAt = Instant.now();
    if (newLocation != null && !newLocation.isBlank()) {
      this.location = newLocation;
    }
    if (newRedactedEvidence != null && !newRedactedEvidence.isBlank()) {
      this.evidence = newRedactedEvidence;
    }
  }

  public void acknowledge() {
    if (this.status == SecurityFindingStatus.RESOLVED) {
      throw new IllegalStateException("Cannot acknowledge an already resolved finding");
    }
    this.status = SecurityFindingStatus.ACKNOWLEDGED;
    this.updatedAt = Instant.now();
  }

  public void resolve(String reason) {
    this.status = SecurityFindingStatus.RESOLVED;
    this.resolutionReason = reason;
    this.resolvedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void acceptRisk(String reason) {
    if (this.severity == SecuritySeverity.CRITICAL) {
      throw new IllegalArgumentException("CRITICAL findings cannot have risk accepted");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Reason is required to accept risk");
    }
    this.status = SecurityFindingStatus.ACCEPTED_RISK;
    this.resolutionReason = reason;
    this.resolvedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void markFalsePositive(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Reason is required to mark as false positive");
    }
    this.status = SecurityFindingStatus.FALSE_POSITIVE;
    this.resolutionReason = reason;
    this.resolvedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  // Getters
  public UUID getId() { return id; }
  public UUID getProjectId() { return projectId; }
  public SecuritySourceType getSourceType() { return sourceType; }
  public String getSourceId() { return sourceId; }
  public SecurityCategory getCategory() { return category; }
  public SecuritySeverity getSeverity() { return severity; }
  public SecurityFindingStatus getStatus() { return status; }
  public String getTitle() { return title; }
  public String getDescription() { return description; }
  public String getEvidence() { return evidence; }
  public String getLocation() { return location; }
  public String getRecommendation() { return recommendation; }
  public String getRuleId() { return ruleId; }
  public String getFingerprint() { return fingerprint; }
  public int getOccurrenceCount() { return occurrenceCount; }
  public String getResolutionReason() { return resolutionReason; }
  public Instant getFirstDetectedAt() { return firstDetectedAt; }
  public Instant getLastDetectedAt() { return lastDetectedAt; }
  public Instant getResolvedAt() { return resolvedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
