package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecuritySummaryContextCollectorTest extends CollectorTestSupport {

  @Test
  @DisplayName("The posture is counts and a verdict, and carries nothing from inside a finding")
  void summaryCarriesNoFindingContent() {
    Fixture fixture = createFullProject("SecurityCollector");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.SECURITY_SUMMARY, fixture.projectId(), ContextReadWindow.DEFAULT);

    assertThat(items).hasSize(1);
    ContextItem summary = items.get(0);
    assertThat(summary.kind()).isEqualTo(ContextKind.SECURITY_NOTE);
    assertThat(summary.provenance().sourceType()).isEqualTo(ContextSourceType.SECURITY_SUMMARY);
    assertThat(summary.provenance().sourceId()).isEqualTo(fixture.projectId().toString());
    assertThat(summary.content()).contains("Open findings: 1").contains("high: 1");

    // Nothing a finding says gets out: not its title, its description, its location, its evidence,
    // or the rule that raised it. A count is the whole contract of this source.
    assertThat(summary.content())
        .doesNotContain(fixture.openFinding().getTitle())
        .doesNotContain(fixture.openFinding().getDescription())
        .doesNotContain(fixture.openFinding().getLocation())
        .doesNotContain(fixture.openFinding().getRecommendation())
        .doesNotContain(fixture.openFinding().getRuleId())
        .doesNotContain(fixture.openFinding().getEvidence());
  }

  @Test
  @DisplayName("A project with no findings still reports a posture")
  void cleanProjectStillReportsAPosture() {
    identity.createAndAuthenticate("SecurityClean");
    var project = projects.create("Clean", "Nothing found yet", "An idea");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.SECURITY_SUMMARY, project.getId(), ContextReadWindow.DEFAULT);

    assertThat(items).hasSize(1);
    assertThat(items.get(0).content()).contains("Security score: 100").contains("Gate: PASS");
    // Dated at the project's own last change, because no finding exists to date it at. Compared
    // against a fresh read: the instance create() returns still carries the nanosecond precision
    // the database does not store.
    assertThat(items.get(0).provenance().recordedAt())
        .isEqualTo(projects.requireReadable(project.getId()).getUpdatedAt());
  }
}
