package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.task.domain.CriterionStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AcceptanceCriteriaContextCollectorTest extends CollectorTestSupport {

  @Test
  @DisplayName("Required and optional criteria are both collected, decided ones included")
  void everyCriterionIsCollected() {
    Fixture fixture = createFullProject("CriteriaCollector");
    tasks.decideCriterion(
        fixture.projectId(),
        fixture.blockedTask().getId(),
        fixture.optionalCriterion().getId(),
        CriterionStatus.SATISFIED,
        "test");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.ACCEPTANCE_CRITERIA, fixture.projectId(), ContextReadWindow.DEFAULT);

    assertThat(items)
        .extracting(ContextItem::id)
        .containsExactlyInAnyOrder(
            "criterion:" + fixture.requiredCriterion().getId(),
            "criterion:" + fixture.optionalCriterion().getId());

    ContextItem required =
        items.stream()
            .filter(item -> item.id().endsWith(fixture.requiredCriterion().getId().toString()))
            .findFirst()
            .orElseThrow();
    assertThat(required.kind()).isEqualTo(ContextKind.CONSTRAINT);
    assertThat(required.content())
        .contains(fixture.requiredCriterion().getDescription())
        .contains("Required: yes")
        .contains("Status: PENDING");
    assertThat(required.provenance().sourceType())
        .isEqualTo(ContextSourceType.ACCEPTANCE_CRITERIA);
    assertThat(required.provenance().sourceId())
        .isEqualTo(fixture.requiredCriterion().getId().toString());
    // Undecided, so it falls back to the owning task's creation instant rather than to "now".
    assertThat(required.provenance().recordedAt())
        .isEqualTo(fixture.blockedTask().getCreatedAt());

    // A criterion someone has already signed off is still part of the definition of done.
    ContextItem satisfied =
        items.stream()
            .filter(item -> item.id().endsWith(fixture.optionalCriterion().getId().toString()))
            .findFirst()
            .orElseThrow();
    assertThat(satisfied.content()).contains("Status: SATISFIED").contains("Required: no");
  }
}
