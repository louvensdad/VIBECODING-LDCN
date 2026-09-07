package com.vibecode.brain.web;

import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.brain.domain.BrainEntryType;
import java.time.Instant;
import java.util.UUID;

public record BrainEntryResponse(
    UUID id,
    UUID projectId,
    BrainEntryType type,
    String title,
    String content,
    String source,
    int version,
    Instant createdAt) {

  public static BrainEntryResponse from(BrainEntry entry) {
    return new BrainEntryResponse(
        entry.getId(),
        entry.getProjectId(),
        entry.getType(),
        entry.getTitle(),
        entry.getContent(),
        entry.getSource(),
        entry.getVersion(),
        entry.getCreatedAt());
  }
}
