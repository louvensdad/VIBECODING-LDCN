package com.vibecode.roadmap.domain;

import java.util.List;
import java.util.UUID;

/**
 * What the platform suggests doing next, and why.
 *
 * <p>The rationale is mandatory: a recommendation the user cannot audit is indistinguishable from
 * an LLM guess, which is exactly what VibeCode exists to replace.
 */
public record NextStepRecommendation(
    UUID projectId,
    UUID recommendedTaskId,
    String recommendedAction,
    String rationale,
    List<String> basedOn,
    boolean blockedByOpenIssues) {

  public NextStepRecommendation {
    basedOn = List.copyOf(basedOn);
  }
}
