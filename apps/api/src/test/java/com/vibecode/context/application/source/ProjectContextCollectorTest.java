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

    assertThat(items)
        .extracting(ContextItem::id)
        .containsExactlyInAnyOrder(
            "project:" + fixture.projectId() + ":identity",
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

  @Test
  @DisplayName("Nothing is filed as NOTE because nothing else fitted")
  void noKindIsAssignedAsAFallback() {
    Fixture fixture = createFullProject("ProjectCollectorKinds");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.PROJECT, fixture.projectId(), ContextReadWindow.DEFAULT);

    // ContextKind.NOTE's own javadoc forbids assigning it because nothing else fitted, and the
    // project row is where that was happening. Nothing this source emits is a note.
    assertThat(items).extracting(ContextItem::kind).doesNotContain(ContextKind.NOTE);

    ContextItem identity =
        items.stream().filter(item -> item.id().endsWith(":identity")).findFirst().orElseThrow();
    assertThat(identity.kind()).isEqualTo(ContextKind.VISION);
    // The name and the description are one statement; both survive verbatim.
    assertThat(identity.content())
        .contains(fixture.project().getName())
        .contains(fixture.project().getDescription());
  }
}
