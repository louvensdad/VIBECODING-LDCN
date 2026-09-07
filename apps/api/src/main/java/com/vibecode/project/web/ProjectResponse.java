package com.vibecode.project.web;

import com.vibecode.project.domain.Project;
import java.time.Instant;
import java.util.UUID;

/** Public representation of a project. JPA entities are never serialized directly. */
public record ProjectResponse(
    UUID id,
    String name,
    String description,
    String originalIdea,
    String status,
    String currentPhase,
    Instant createdAt,
    Instant updatedAt) {

  public static ProjectResponse from(Project project) {
    return new ProjectResponse(
        project.getId(),
        project.getName(),
        project.getDescription(),
        project.getOriginalIdea(),
        project.getStatus().name(),
        project.getCurrentPhase(),
        project.getCreatedAt(),
        project.getUpdatedAt());
  }
}
