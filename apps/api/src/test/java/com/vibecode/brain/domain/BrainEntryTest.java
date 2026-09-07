package com.vibecode.brain.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BrainEntryTest {

  private static final UUID PROJECT = UUID.randomUUID();

  @Test
  void anEntryStartsAtVersionOneAndRecordsItsProvenance() {
    BrainEntry entry =
        new BrainEntry(
            PROJECT, BrainEntryType.DECISION, "Use PostgreSQL", "Relational state", "user");

    assertThat(entry.getId()).isNotNull();
    assertThat(entry.getVersion()).isEqualTo(1);
    assertThat(entry.getSource()).isEqualTo("user");
    assertThat(entry.getCreatedAt()).isNotNull();
  }

  @Test
  void theBrainGroupsEntriesByType() {
    BrainEntry vision =
        new BrainEntry(PROJECT, BrainEntryType.VISION, "Vision", "Own the context", "user");
    BrainEntry decision =
        new BrainEntry(PROJECT, BrainEntryType.DECISION, "Modular monolith", "Start simple", "user");
    BrainEntry rule =
        new BrainEntry(PROJECT, BrainEntryType.RULE, "No secrets in prompts", "Ever", "user");

    ProjectBrain brain = new ProjectBrain(PROJECT, List.of(vision, decision, rule));

    assertThat(brain.size()).isEqualTo(3);
    assertThat(brain.byType()).containsOnlyKeys(
        BrainEntryType.VISION, BrainEntryType.DECISION, BrainEntryType.RULE);
    assertThat(brain.of(BrainEntryType.VISION)).containsExactly(vision);
    assertThat(brain.latest(BrainEntryType.RULE)).isEqualTo(rule);
    assertThat(brain.latest(BrainEntryType.ERROR)).isNull();
  }
}
