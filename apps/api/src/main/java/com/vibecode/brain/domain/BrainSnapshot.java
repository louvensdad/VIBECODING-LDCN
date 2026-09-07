package com.vibecode.brain.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An immutable point-in-time copy of a project's memory.
 *
 * <p>Reserved for the versioning step: snapshots let the user compare what the project believed
 * before and after a model handoff. Nothing writes snapshots yet.
 */
public record BrainSnapshot(UUID projectId, int version, Instant takenAt, List<BrainEntry> entries) {

  public BrainSnapshot {
    entries = List.copyOf(entries);
  }
}
