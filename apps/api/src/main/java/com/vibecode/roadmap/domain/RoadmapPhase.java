package com.vibecode.roadmap.domain;

import java.util.List;
import java.util.UUID;

/** A named group of steps — "Foundation", "Auth", "Launch". */
public record RoadmapPhase(UUID id, String name, String goal, List<UUID> taskIds, int order) {

  public RoadmapPhase {
    taskIds = List.copyOf(taskIds);
  }
}
