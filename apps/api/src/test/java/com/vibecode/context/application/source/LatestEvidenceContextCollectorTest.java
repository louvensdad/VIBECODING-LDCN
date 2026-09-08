package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.output.domain.EvidenceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LatestEvidenceContextCollectorTest extends CollectorTestSupport {

  @Test
  @DisplayName("Evidence is collected as recorded, and the window bounds the query only")
  void evidenceIsCollectedFaithfully() {
    Fixture fixture = createFullProject("EvidenceCollector");
    evidence.record(
        fixture.projectId(),
        fixture.blockedTask().getId(),
        EvidenceType.TEST_RESULT,
        "Tests run: 4, Failures: 0, Errors: 0",
        "maven");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.LATEST_EVIDENCE, fixture.projectId(), ContextReadWindow.DEFAULT);

    assertThat(items).hasSize(2);
    assertThat(items)
        .allSatisfy(
            item -> {
              assertThat(item.kind()).isEqualTo(ContextKind.EVIDENCE);
              assertThat(item.provenance().sourceType())
                  .isEqualTo(ContextSourceType.LATEST_EVIDENCE);
              assertThat(item.provenance().projectId()).isEqualTo(fixture.projectId());
            });

    ContextItem failing =
        items.stream()
            .filter(item -> item.id().equals("evidence:" + fixture.failingEvidenceId()))
            .findFirst()
            .orElseThrow();
    // The failure is still here even though a passing run followed it. A collector that kept only
    // the newest run would erase the trail that makes evidence worth having.
    assertThat(failing.content()).contains("BUILD FAILURE");
    assertThat(failing.provenance().sourceId())
        .isEqualTo(fixture.failingEvidenceId().toString());
  }

  @Test
  @DisplayName("A narrower window reads fewer rows, and says so by returning fewer candidates")
  void windowBoundsTheQuery() {
    Fixture fixture = createFullProject("EvidenceWindow");
    evidence.record(
        fixture.projectId(),
        fixture.blockedTask().getId(),
        EvidenceType.TEST_RESULT,
        "Tests run: 4, Failures: 0, Errors: 0",
        "maven");

    assertThat(
            candidates.collectFrom(
                ContextSourceType.LATEST_EVIDENCE, fixture.projectId(), new ContextReadWindow(1)))
        .hasSize(1);
  }
}
