package com.vibecode.prompt.domain;

import java.util.List;
import java.util.UUID;

/**
 * The project context a prompt is allowed to carry.
 *
 * <p>Assembled from official memory only, and deliberately explicit: nothing reaches a prompt
 * because it happened to be nearby. Secrets and credentials are never part of this record, and no
 * field is populated from environment or configuration.
 */
public record PromptContext(
    UUID projectId,
    String projectSummary,
    List<String> relevantMemory,
    List<String> activeRules,
    List<String> knownProblems,
    String currentTaskSummary) {

  public PromptContext {
    relevantMemory = List.copyOf(relevantMemory);
    activeRules = List.copyOf(activeRules);
    knownProblems = List.copyOf(knownProblems);
  }
}
