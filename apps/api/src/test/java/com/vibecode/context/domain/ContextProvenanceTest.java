package com.vibecode.context.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextProvenanceTest {

  private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final Instant OBSERVED = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void provenanceCarriesSourceTypeSourceIdProjectAndTimestamp() {
    ContextProvenance provenance =
        new ContextProvenance(
            ContextSource.versioned(ContextSourceType.BRAIN_DECISION, "dec-7", 4),
            PROJECT,
            OBSERVED);

    assertThat(provenance.sourceType()).isEqualTo(ContextSourceType.BRAIN_DECISION);
    assertThat(provenance.sourceId()).isEqualTo("dec-7");
    assertThat(provenance.projectId()).isEqualTo(PROJECT);
    assertThat(provenance.recordedAt()).isEqualTo(OBSERVED);
    assertThat(provenance.sourceVersion()).contains(4);
  }

  @Test
  void aVersionIsOptionalBecauseNotEverySourceRecordHasOne() {
    ContextProvenance provenance =
        new ContextProvenance(
            ContextSource.of(ContextSourceType.CURRENT_STATE, "state-1"), PROJECT, OBSERVED);

    assertThat(provenance.sourceVersion()).isEmpty();
  }

  @Test
  void anItemWithoutProvenanceCannotBeConstructed() {
    assertThatThrownBy(
            () ->
                new ContextItem(
                    "i-1", ContextKind.STATE, "synthetic label", "synthetic content", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot exist without provenance");
  }

  @Test
  void thereIsNoWayToBuildAnItemFirstAndAttachProvenanceLater() {
    // Every constructor must demand provenance, and nothing may set it afterwards.
    for (Constructor<?> constructor : ContextItem.class.getDeclaredConstructors()) {
      assertThat(constructor.getParameterTypes())
          .as("constructor %s must take provenance", constructor)
          .contains(ContextProvenance.class);
    }
    for (Method method : ContextItem.class.getMethods()) {
      assertThat(method.getName())
          .as("ContextItem must expose no mutator")
          .doesNotStartWith("set")
          .doesNotStartWith("with");
    }
  }

  @Test
  void provenanceRefusesToBeIncomplete() {
    ContextSource source = ContextSource.of(ContextSourceType.PROJECT, "project-1");

    assertThatThrownBy(() -> new ContextProvenance(null, PROJECT, OBSERVED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ContextProvenance(source, null, OBSERVED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ContextProvenance(source, PROJECT, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ContextSource.of(ContextSourceType.PROJECT, "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
