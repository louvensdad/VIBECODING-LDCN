package com.vibecode.wellness.domain;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** Per-user wellness settings. Opt-in by default: an empty signal set means no nudges at all. */
public record WellnessPreferences(
    UUID projectId, Set<WellnessSignal> enabledSignals, Duration focusBlock, Duration breakLength) {

  public WellnessPreferences {
    enabledSignals = Set.copyOf(enabledSignals);
  }

  public static WellnessPreferences disabled(UUID projectId) {
    return new WellnessPreferences(
        projectId, Set.of(), Duration.ofMinutes(25), Duration.ofMinutes(5));
  }
}
