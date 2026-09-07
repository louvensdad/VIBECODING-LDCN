package com.vibecode.guardian.domain;

import com.vibecode.state.domain.ProjectState;
import com.vibecode.task.domain.Task;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The context supplied to security rules for deterministic evaluation. */
public record SecurityInspectionContext(
    UUID projectId,
    UUID userId,
    SecuritySourceType sourceType,
    String sourceId,
    String rawContent,
    Optional<Task> task,
    Optional<ProjectState> projectState,
    Map<String, String> relevantMetadata) {

  public SecurityInspectionContext {
    Objects.requireNonNull(projectId, "projectId cannot be null");
    Objects.requireNonNull(sourceType, "sourceType cannot be null");
    sourceId = sourceId == null ? "unspecified" : sourceId;
    rawContent = rawContent == null ? "" : rawContent;
    task = task == null ? Optional.empty() : task;
    projectState = projectState == null ? Optional.empty() : projectState;
    relevantMetadata = relevantMetadata == null ? Map.of() : Map.copyOf(relevantMetadata);
  }

  public static SecurityInspectionContext forText(
      UUID projectId, UUID userId, SecuritySourceType sourceType, String sourceId, String content) {
    return new SecurityInspectionContext(
        projectId,
        userId,
        sourceType,
        sourceId,
        content,
        Optional.empty(),
        Optional.empty(),
        Map.of());
  }
}
