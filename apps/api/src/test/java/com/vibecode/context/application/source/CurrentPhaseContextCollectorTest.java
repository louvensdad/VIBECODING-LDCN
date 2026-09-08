package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CurrentPhaseContextCollectorTest extends CollectorTestSupport {

  @Test
  @DisplayName("The phase holding the current task is collected under its own source")
  void currentPhaseIsCollected() {
    Fixture fixture = createFullProject("PhaseCollector");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.CURRENT_PHASE, fixture.projectId(), ContextReadWindow.DEFAULT);

    assertThat(items).hasSize(1);
    ContextItem item = items.get(0);
    assertThat(item.id()).isEqualTo("current-phase:" + fixture.firstPhase().getId());
    assertThat(item.kind()).isEqualTo(ContextKind.OBJECTIVE);
    assertThat(item.label()).isEqualTo(fixture.firstPhase().getTitle());
    assertThat(item.content()).contains(fixture.firstPhase().getDescription());
    assertThat(item.provenance().sourceType()).isEqualTo(ContextSourceType.CURRENT_PHASE);
    assertThat(item.provenance().sourceId()).isEqualTo(fixture.firstPhase().getId().toString());
    assertThat(item.provenance().recordedAt()).isEqualTo(fixture.firstPhase().getUpdatedAt());
  }
}
