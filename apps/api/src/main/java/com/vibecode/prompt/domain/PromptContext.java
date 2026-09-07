package com.vibecode.prompt.domain;

import java.util.List;
import java.util.UUID;

/**
 * The project context a prompt is allowed to carry.
 *
 * <p>Assembled from recorded state only, and deliberately explicit: nothing reaches a prompt
 * because it happened to be nearby. There is no credential field here, and none may be added —
 * secrets never enter a prompt automatically.
 */
public record PromptContext(
    UUID projectId,
    String projectName,
    String projectIdea,
    String currentPhase,
    String taskTitle,
    String taskObjective,
    List<String> acceptanceCriteria,
    List<String> completedWork,
    List<String> activeProblems,
    List<String> relevantDecisions,
    String latestEvidence,
    List<String> latestSignals) {

  public PromptContext {
    acceptanceCriteria = List.copyOf(acceptanceCriteria);
    completedWork = List.copyOf(completedWork);
    activeProblems = List.copyOf(activeProblems);
    relevantDecisions = List.copyOf(relevantDecisions);
    latestSignals = List.copyOf(latestSignals);
  }
}
