package com.vibecode.context.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.brain.domain.BrainEntryType;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the correspondence between official memory and context meaning.
 *
 * <p>The compiler is the primary guard — the mapping's switch has no {@code default}, so a missing
 * or added brain entry type stops the build. These tests cover what the compiler cannot see: that no
 * two entry types were collapsed onto one kind, and that the pairs most likely to be conflated are
 * still distinct.
 */
class BrainEntryContextMappingTest {

  @Test
  void everyBrainEntryTypeHasAKindAndNoTwoShareOne() {
    // Driven from values(), so a fourteenth entry type fails here as well as at compile time.
    Map<ContextKind, BrainEntryType> claimedBy = new EnumMap<>(ContextKind.class);
    for (BrainEntryType entryType : BrainEntryType.values()) {
      ContextKind kind = BrainEntryContextMapping.kindOf(entryType);

      assertThat(kind).as("%s must map to a kind", entryType).isNotNull();
      BrainEntryType alreadyClaimed = claimedBy.put(kind, entryType);
      assertThat(alreadyClaimed)
          .as("%s and %s cannot both mean %s", alreadyClaimed, entryType, kind)
          .isNull();
    }
    assertThat(claimedBy).hasSize(BrainEntryType.values().length);
    assertThat(BrainEntryType.values()).hasSize(13);
  }

  @Test
  void theKindIsNamedAfterTheEntryTypeSoNoCollectorHasToGuess() {
    for (BrainEntryType entryType : BrainEntryType.values()) {
      assertThat(BrainEntryContextMapping.kindOf(entryType).name()).isEqualTo(entryType.name());
    }
  }

  @Test
  void aTechnologyIsNotAnArchitecture() {
    assertThat(BrainEntryContextMapping.kindOf(BrainEntryType.TECHNOLOGY))
        .isNotEqualTo(BrainEntryContextMapping.kindOf(BrainEntryType.ARCHITECTURE));
  }

  @Test
  void anErrorIsNotANote() {
    assertThat(BrainEntryContextMapping.kindOf(BrainEntryType.ERROR))
        .isNotEqualTo(BrainEntryContextMapping.kindOf(BrainEntryType.NOTE));
  }

  @Test
  void aSolutionIsNotACompletedStep() {
    assertThat(BrainEntryContextMapping.kindOf(BrainEntryType.SOLUTION))
        .isNotEqualTo(BrainEntryContextMapping.kindOf(BrainEntryType.COMPLETED_STEP));
  }

  @Test
  void aNextStepIsNotTheCurrentState() {
    assertThat(BrainEntryContextMapping.kindOf(BrainEntryType.NEXT_STEP))
        .isNotEqualTo(BrainEntryContextMapping.kindOf(BrainEntryType.CURRENT_STATE));
  }

  @Test
  void aPromptResultIsNotANote() {
    assertThat(BrainEntryContextMapping.kindOf(BrainEntryType.PROMPT_RESULT))
        .isNotEqualTo(BrainEntryContextMapping.kindOf(BrainEntryType.NOTE));
  }

  @Test
  void aMemoryEntrysOriginIsAlwaysBrainEntryWhateverItMeans() {
    // The worked example from the javadoc, asserted: meaning goes on the kind axis, never the
    // source axis.
    ContextItem technology =
        new ContextItem(
            "i-tech",
            BrainEntryContextMapping.kindOf(BrainEntryType.TECHNOLOGY),
            "synthetic technology entry",
            "PostgreSQL 16",
            new ContextProvenance(
                ContextSource.versioned(ContextSourceType.BRAIN_ENTRY, "entry-42", 2),
                java.util.UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                java.time.Instant.parse("2026-01-01T00:00:00Z")));

    assertThat(technology.provenance().sourceType()).isEqualTo(ContextSourceType.BRAIN_ENTRY);
    assertThat(technology.provenance().sourceId()).isEqualTo("entry-42");
    assertThat(technology.kind()).isEqualTo(ContextKind.TECHNOLOGY);
  }

  @Test
  void aMissingEntryTypeIsRejectedRatherThanGivenAFallbackKind() {
    assertThatThrownBy(() -> BrainEntryContextMapping.kindOf(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
