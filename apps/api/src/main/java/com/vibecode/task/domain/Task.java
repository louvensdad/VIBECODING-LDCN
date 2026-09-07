package com.vibecode.task.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One step of the tracked project.
 *
 * <p>A task is not done because someone says so: {@code completionCriteria} states what evidence
 * closes it, and the Output Analyzer is what supplies that evidence. Modelled as a value type in
 * this phase — tasks get their own table when the roadmap becomes editable.
 */
public record Task(
    UUID id,
    UUID projectId,
    String title,
    String objective,
    TaskStatus status,
    List<UUID> dependsOn,
    List<String> completionCriteria,
    List<String> risks,
    String result,
    Instant createdAt,
    Instant updatedAt) {

  public Task {
    dependsOn = List.copyOf(dependsOn);
    completionCriteria = List.copyOf(completionCriteria);
    risks = List.copyOf(risks);
  }

  public static Task create(UUID projectId, String title, String objective) {
    Instant now = Instant.now();
    return new Task(
        UUID.randomUUID(),
        projectId,
        title,
        objective,
        TaskStatus.TODO,
        List.of(),
        List.of(),
        List.of(),
        null,
        now,
        now);
  }

  public boolean isBlockedByDependencies() {
    return status == TaskStatus.BLOCKED || !dependsOn.isEmpty() && status == TaskStatus.TODO;
  }
}
