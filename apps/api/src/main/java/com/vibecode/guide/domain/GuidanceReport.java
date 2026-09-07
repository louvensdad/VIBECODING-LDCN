package com.vibecode.guide.domain;

import java.util.List;
import java.util.UUID;

/**
 * A plain answer to: where are we, what is done, what is missing, what now, and why that.
 *
 * <p>{@code evidence} names the Brain entries and analyses the answer rests on, so the user can
 * check the reasoning instead of trusting it.
 */
public record GuidanceReport(
    UUID projectId,
    String whereWeAre,
    List<String> whatIsDone,
    List<String> whatIsMissing,
    String recommendedNow,
    String whyThisIsNext,
    List<String> evidence) {

  public GuidanceReport {
    whatIsDone = List.copyOf(whatIsDone);
    whatIsMissing = List.copyOf(whatIsMissing);
    evidence = List.copyOf(evidence);
  }
}
