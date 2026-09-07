package com.vibecode.guardian.web;

import com.vibecode.guardian.domain.SecurityCategory;
import com.vibecode.guardian.domain.SecurityFinding;
import com.vibecode.guardian.domain.SecurityFindingStatus;
import com.vibecode.guardian.domain.SecuritySeverity;
import com.vibecode.guardian.domain.SecuritySourceType;
import java.time.Instant;
import java.util.UUID;

public record SecurityFindingResponse(
    UUID id,
    UUID projectId,
    SecuritySourceType sourceType,
    String sourceId,
    SecurityCategory category,
    SecuritySeverity severity,
    SecurityFindingStatus status,
    String title,
    String description,
    String evidence,
    String location,
    String recommendation,
    String ruleId,
    int occurrenceCount,
    String resolutionReason,
    Instant firstDetectedAt,
    Instant lastDetectedAt,
    Instant resolvedAt) {

  public static SecurityFindingResponse from(SecurityFinding f) {
    return new SecurityFindingResponse(
        f.getId(),
        f.getProjectId(),
        f.getSourceType(),
        f.getSourceId(),
        f.getCategory(),
        f.getSeverity(),
        f.getStatus(),
        f.getTitle(),
        f.getDescription(),
        f.getEvidence(),
        f.getLocation(),
        f.getRecommendation(),
        f.getRuleId(),
        f.getOccurrenceCount(),
        f.getResolutionReason(),
        f.getFirstDetectedAt(),
        f.getLastDetectedAt(),
        f.getResolvedAt());
  }
}

