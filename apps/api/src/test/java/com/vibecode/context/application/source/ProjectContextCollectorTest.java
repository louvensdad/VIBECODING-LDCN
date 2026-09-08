package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProjectContextCollectorTest extends CollectorTestSupport {

  @Test
  @DisplayName("The project row becomes candidates that name the project row")
  void projectFieldsAreCollectedFaithfully() {
    Fixture fixture = createFullProject("ProjectCollector");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.PROJECT, fixture.projectId(), ContextReadWindow.DEFAULT);

    assertThat(items)
        .allSatisfy(
            item -> {
              assertThat(item.provenance().sourceType()).isEqualTo(ContextSourceType.PROJECT);
              assertThat(item.provenance().sourceId())
                  .isEqualTo(fixture.projectId().toString());
              assertThat(item.provenance().projectId()).isEqualTo(fixture.projectId());
              assertThat(item.provenance().recordedAt())
                  .isEqualTo(fixture.project().getUpdatedAt());
              // The project row has no revision, so claiming one would be an invention.
              assertThat(item.provenance().sourceVersion()).isEmpty();
            });

    assertThat(items).extracting(ContextItem::id).contains(
        "project:" + fixture.projectId() + ":name",
        "project:" + fixture.projectId() + ":description",
        "project:" + fixture.projectId() + ":idea",
        "project:" + fixture.projectId() + ":status");

    ContextItem idea =
        items.stream()
            .filter(item -> item.id().endsWith(":idea"))
            .findFirst()
            .orElseThrow();
    assertThat(idea.kind()).isEqualTo(ContextKind.VISION);
    // Content is the record's text, not a paraphrase of it.
    assertThat(idea.content()).isEqualTo(fixture.project().getOriginalIdea());
  }
}
