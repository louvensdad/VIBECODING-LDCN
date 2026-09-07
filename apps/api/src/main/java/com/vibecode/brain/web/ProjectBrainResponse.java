package com.vibecode.brain.web;

import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.brain.domain.ProjectBrain;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The Project Brain as the workspace reads it: entries plus a count per type. */
public record ProjectBrainResponse(
    UUID projectId,
    int entryCount,
    Map<BrainEntryType, Integer> countByType,
    List<BrainEntryResponse> entries) {

  public static ProjectBrainResponse from(ProjectBrain brain) {
    Map<BrainEntryType, Integer> counts =
        brain.byType().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
    return new ProjectBrainResponse(
        brain.projectId(),
        brain.size(),
        counts,
        brain.entries().stream().map(BrainEntryResponse::from).toList());
  }
}
