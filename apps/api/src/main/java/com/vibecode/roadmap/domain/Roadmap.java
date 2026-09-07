package com.vibecode.roadmap.domain;

import java.util.List;
import java.util.UUID;

/** The planned path of a project: ordered phases, each holding tasks. */
public record Roadmap(UUID projectId, List<RoadmapPhase> phases) {

  public Roadmap {
    phases = List.copyOf(phases);
  }

  public static Roadmap empty(UUID projectId) {
    return new Roadmap(projectId, List.of());
  }
}
