package com.vibecode.guide.domain;

import com.vibecode.prompt.domain.PromptType;
import java.util.List;
import java.util.UUID;

/**
 * What to do next, and why.
 *
 * <p>{@code reason} is mandatory. A recommendation the user cannot audit is indistinguishable from
 * a guess, which is exactly what this engine exists to replace — every field here comes from
 * recorded state, never from a model.
 */
public record NextStepRecommendation(
    NextStepType type,
    String title,
    String reason,
    UUID taskId,
    NextStepPriority priority,
    List<String> blockingIssues,
    List<String> requiredActions,
    PromptType suggestedPromptType) {

  public NextStepRecommendation {
    blockingIssues = List.copyOf(blockingIssues);
    requiredActions = List.copyOf(requiredActions);
  }
}
