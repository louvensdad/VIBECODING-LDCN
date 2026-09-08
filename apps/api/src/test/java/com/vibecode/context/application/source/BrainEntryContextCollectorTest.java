package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BrainEntryContextCollectorTest extends CollectorTestSupport {

  @Test
  @DisplayName("A brain entry keeps its id, its version, its title and its text")
  void brainEntriesAreCollectedFaithfully() {
    Fixture fixture = createFullProject("BrainCollector");
    List<BrainEntry> entries = brain.list(fixture.projectId());

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.BRAIN_ENTRY, fixture.projectId(), ContextReadWindow.DEFAULT);

    assertThat(items).hasSize(entries.size());
    for (BrainEntry entry : entries) {
      ContextItem item =
          items.stream()
              .filter(candidate -> candidate.id().equals("brain:" + entry.getId()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("Entry not collected: " + entry.getId()));

      assertThat(item.label()).isEqualTo(entry.getTitle());
      assertThat(item.content()).isEqualTo(entry.getContent());
      assertThat(item.provenance().sourceType()).isEqualTo(ContextSourceType.BRAIN_ENTRY);
      assertThat(item.provenance().sourceId()).isEqualTo(entry.getId().toString());
      assertThat(item.provenance().projectId()).isEqualTo(fixture.projectId());
      assertThat(item.provenance().recordedAt()).isEqualTo(entry.getCreatedAt());
      // Brain entries are versioned, so an unversioned reference would not resolve back to the
      // exact text that was used.
      assertThat(item.provenance().sourceVersion()).contains(entry.getVersion());
    }
  }
}
