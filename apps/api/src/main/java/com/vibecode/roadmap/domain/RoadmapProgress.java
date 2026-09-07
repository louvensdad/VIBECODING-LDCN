package com.vibecode.roadmap.domain;

/**
 * Progress measured in completed steps, never in elapsed effort.
 *
 * <p>A percentage is derived, not stored, so it cannot drift away from the tasks it summarizes.
 */
public record RoadmapProgress(int totalTasks, int completedTasks, String currentPhase) {

  public int percentComplete() {
    return totalTasks == 0 ? 0 : Math.round(completedTasks * 100f / totalTasks);
  }
}
