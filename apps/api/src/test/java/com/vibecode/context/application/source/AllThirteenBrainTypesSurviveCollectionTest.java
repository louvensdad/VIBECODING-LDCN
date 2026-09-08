package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.context.domain.BrainEntryContextMapping;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The behavioural guarantee that replaced an import rule.
 *
 * <p>An ArchUnit rule forbidding the collectors to see the brain module could only say that nobody
 * imports {@code BrainEntryType} — which is exactly what a collector that reads brain entries has to
 * do. It could never say the thing that matters: that the kind an entry arrives with is the kind the
 * mapping decided, and not one a collector made up. This test says that, for every type there is.
 *
 * <p>It is driven from {@link BrainEntryType#values()} rather than a written-out list, so a
 * fourteenth entry type fails it on the day it is added instead of the day someone notices.
 */
class AllThirteenBrainTypesSurviveCollectionTest extends CollectorTestSupport {

  @Test
  @DisplayName("Every brain entry type is collected, with the kind the mapping gives it")
  void everyBrainEntryTypeSurvives() {
    Fixture fixture = createFullProject("AllThirteen");

    // The fixture writes exactly one entry of each type, so the two sides are comparable one to one.
    Map<BrainEntryType, BrainEntry> written =
        brain.list(fixture.projectId()).stream()
            .collect(Collectors.toMap(BrainEntry::getType, Function.identity()));
    assertThat(written).hasSize(BrainEntryType.values().length);

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.BRAIN_ENTRY, fixture.projectId(), ContextReadWindow.DEFAULT);

    for (BrainEntryType type : BrainEntryType.values()) {
      BrainEntry entry = written.get(type);
      assertThat(entry).as("no entry written for %s", type).isNotNull();

      ContextItem item =
          items.stream()
              .filter(candidate -> candidate.id().equals("brain:" + entry.getId()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "Entry of type " + type + " was dropped during collection"));

      // The mapping is the only authority. If a collector ever grows its own switch, this is where
      // it stops being true.
      assertThat(item.kind())
          .as("collected kind for %s", type)
          .isEqualTo(BrainEntryContextMapping.kindOf(type));
      // The origin stays the origin: meaning never gets folded into the source type.
      assertThat(item.provenance().sourceType()).isEqualTo(ContextSourceType.BRAIN_ENTRY);
    }

    assertThat(BrainEntryType.values()).hasSize(13);
  }
}
