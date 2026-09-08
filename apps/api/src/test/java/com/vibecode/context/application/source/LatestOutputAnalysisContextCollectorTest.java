package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LatestOutputAnalysisContextCollectorTest extends CollectorTestSupport {

  @Test
  @DisplayName("The verdict on a run is its own candidate, alongside the evidence it judged")
  void analysesAreCollectedFaithfully() {
    Fixture fixture = createFullProject("AnalysisCollector");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.LATEST_OUTPUT_ANALYSIS,
            fixture.projectId(),
            ContextReadWindow.DEFAULT);

    ContextItem analysis =
        items.stream()
            .filter(item -> item.id().equals("analysis:" + fixture.failingAnalysisId()))
            .findFirst()
            .orElseThrow();

    assertThat(analysis.kind()).isEqualTo(ContextKind.EVIDENCE);
    assertThat(analysis.provenance().sourceType())
        .isEqualTo(ContextSourceType.LATEST_OUTPUT_ANALYSIS);
    assertThat(analysis.provenance().sourceId())
        .isEqualTo(fixture.failingAnalysisId().toString());
    assertThat(analysis.provenance().projectId()).isEqualTo(fixture.projectId());
    assertThat(analysis.content()).contains("Requires correction: yes");
  }
}
