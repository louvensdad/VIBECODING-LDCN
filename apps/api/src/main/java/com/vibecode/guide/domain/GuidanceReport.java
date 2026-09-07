package com.vibecode.guide.domain;

import java.util.List;
import java.util.UUID;

/**
 * The Project Guide's answer: where you are, what is done, what is in the way, and what to do next.
 *
 * <p>Assembled from recorded state only. The Guide orients; it never executes anything.
 */
public record GuidanceReport(
    UUID projectId,
    String whereYouAre,
    List<String> whatWasCompleted,
    List<String> whatIsMissing,
    List<String> activeProblems,
    NextStepRecommendation recommendedNextStep,
    String reason,
    int progressPercentage) {

  public GuidanceReport {
    whatWasCompleted = List.copyOf(whatWasCompleted);
    whatIsMissing = List.copyOf(whatIsMissing);
    activeProblems = List.copyOf(activeProblems);
  }
}
