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
  @DisplayName("The project's identity is filed as PROJECT_IDENTITY, not as a nearest fit")
  void identityIsItsOwnKind() {
    Fixture fixture = createFullProject("ProjectCollectorKinds");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.PROJECT, fixture.projectId(), ContextReadWindow.DEFAULT);

    // ContextKind.NOTE's own javadoc forbids assigning it because nothing else fitted, and the
    // project row is where that was happening. Nothing this source emits is a note.
    assertThat(items).extracting(ContextItem::kind).doesNotContain(ContextKind.NOTE);

    ContextItem identity =
        items.stream().filter(item -> item.id().endsWith(":identity")).findFirst().orElseThrow();
    assertThat(identity.kind()).isEqualTo(ContextKind.PROJECT_IDENTITY);
    // Both of the constants this filing passed through on its way here, asserted explicitly so a
    // regression to either fails by name rather than by a count.
    assertThat(identity.kind()).isNotEqualTo(ContextKind.VISION);
    assertThat(identity.kind()).isNotEqualTo(ContextKind.NOTE);
    // The name and the description are one statement; both survive verbatim.
    assertThat(identity.content())
        .contains(fixture.project().getName())
        .contains(fixture.project().getDescription());
  }

  @Test
  @DisplayName("The name and the description never enter as VISION or as NOTE")
  void neitherNameNorDescriptionEntersAsASemanticFallback() {
    Fixture fixture = createFullProject("ProjectCollectorNoFallback");
    String name = fixture.project().getName();
    String description = fixture.project().getDescription();

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.PROJECT, fixture.projectId(), ContextReadWindow.DEFAULT);

    // No item of either kind may carry the name or the description. VISION still legitimately
    // appears in this collector's output — the original idea the project was created from is the
    // product vision and nothing else — so a blanket "emits no VISION" assertion would be false
    // for an honest reason and would have to be deleted the moment anyone read it. This asserts the
    // thing that was actually wrong: identity being filed under a kind that means something else.
    assertThat(items)
        .filteredOn(item -> item.kind() == ContextKind.VISION || item.kind() == ContextKind.NOTE)
        .allSatisfy(
            item -> {
              assertThat(item.content()).doesNotContain(name);
              assertThat(item.content()).doesNotContain(description);
            });

    // And the one VISION item there is, is the original idea verbatim — not a paraphrase, and not
    // the identity wearing a borrowed kind.
    assertThat(items)
        .filteredOn(item -> item.kind() == ContextKind.VISION)
        .singleElement()
        .satisfies(
            item -> assertThat(item.content()).isEqualTo(fixture.project().getOriginalIdea()));
  }

  @Test
  @DisplayName("A project with no description gets no invented description")
  void anAbsentDescriptionIsAnAbsentRecord() {
    identity.createAndAuthenticate("ProjectNoDescription");
    var project = projects.create("Nameless idea", null, "The idea the project started from");

    ContextItem item =
        candidates
            .collectFrom(ContextSourceType.PROJECT, project.getId(), ContextReadWindow.DEFAULT)
            .stream()
            .filter(candidate -> candidate.id().endsWith(":identity"))
            .findFirst()
            .orElseThrow();

    // Exactly the name and nothing more: no separator left dangling, no template sentence, nothing
    // a later reader could mistake for something the project said about itself.
    assertThat(item.content()).isEqualTo("Nameless idea");
    assertThat(item.kind()).isEqualTo(ContextKind.PROJECT_IDENTITY);
  }
}
