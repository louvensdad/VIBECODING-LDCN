package com.vibecode.brain.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The official structured memory of a project: the aggregate view over its entries.
 *
 * <p>This is a read model assembled on demand, not a stored row. The entries are the durable state.
 */
public record ProjectBrain(UUID projectId, List<BrainEntry> entries) {

  public ProjectBrain {
    entries = List.copyOf(entries);
  }

  public Map<BrainEntryType, List<BrainEntry>> byType() {
    return entries.stream().collect(Collectors.groupingBy(BrainEntry::getType));
  }

  public List<BrainEntry> of(BrainEntryType type) {
    return entries.stream().filter(entry -> entry.getType() == type).toList();
  }

  /** The most recent entry of a type, which is the one that currently holds. */
  public BrainEntry latest(BrainEntryType type) {
    return of(type).stream().findFirst().orElse(null);
  }

  public int size() {
    return entries.size();
  }
}
