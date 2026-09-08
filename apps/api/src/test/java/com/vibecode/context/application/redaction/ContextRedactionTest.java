package com.vibecode.context.application.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What redaction covers on a context item, and what it leaves alone.
 *
 * <p>This does not re-test the Guardian's patterns; they have their own suite and this module is
 * deliberately a consumer of them rather than a second opinion. What is tested here is the wiring:
 * that content goes through, that the label goes through too, and that the item's identity and
 * provenance do not - because a redacted handle is a broken trace for no gain.
 *
 * <p>All fixtures are synthetic strings shaped like credentials. None of them is one.
 */
class ContextRedactionTest {

  private static final UUID PROJECT = UUID.randomUUID();
  private static final Instant OBSERVED_AT = Instant.parse("2026-03-01T10:15:30Z");
  private static final String FIXTURE = "vc_redaction_fixture_112233";

  @Test
  @DisplayName("A secret-shaped value in the content is replaced before anything else sees it")
  void contentIsRedacted() {
    ContextItem redacted =
        ContextRedaction.redact(
            item("Deployment note", "Set TOKEN=" + FIXTURE + " before running the migration."));

    assertThat(redacted.content()).doesNotContain(FIXTURE);
    assertThat(redacted.content()).contains("[REDACTED]");
    // The rest of the item survives: redaction removes a value, it does not discard the sentence
    // that gave the value its meaning.
    assertThat(redacted.content()).contains("before running the migration.");
  }

  @Test
  @DisplayName("A secret-shaped value in the label is redacted too")
  void labelIsRedacted() {
    // A label is stored and displayed and is written by the same collectors from the same records.
    // It leaks just as effectively as a body, and more quietly, because it is short enough that
    // nobody reads it as payload.
    ContextItem redacted =
        ContextRedaction.redact(item("API_KEY=" + FIXTURE, "Ordinary synthetic content."));

    assertThat(redacted.label()).doesNotContain(FIXTURE);
    assertThat(redacted.label()).contains("[REDACTED]");
  }

  @Test
  @DisplayName("Identity and provenance pass through untouched")
  void handlesAreNotRedacted() {
    ContextItem original = item("Ordinary label", "PASSWORD=" + FIXTURE);
    ContextItem redacted = ContextRedaction.redact(original);

    assertThat(redacted.id()).isEqualTo(original.id());
    assertThat(redacted.kind()).isEqualTo(original.kind());
    assertThat(redacted.provenance()).isEqualTo(original.provenance());
    assertThat(redacted.provenance().sourceId()).isEqualTo("entry-1");
  }

  @Test
  @DisplayName("An item with nothing to redact comes back as the same object")
  void cleanItemsAreNotCopied() {
    ContextItem clean = item("Ordinary label", "Ordinary synthetic content with nothing in it.");

    assertThat(ContextRedaction.redact(clean)).isSameAs(clean);
  }

  @Test
  @DisplayName("Redaction changes the measured size, which is why it runs before the budget does")
  void redactionChangesWhatThereIsToMeasure() {
    ContextItem original = item("Ordinary label", "TOKEN=" + FIXTURE);
    ContextItem redacted = ContextRedaction.redact(original);

    // The budget must measure this second number, because it is the text that would leave. Sizing
    // a pack by the first would be sizing it by something nobody will ever see.
    assertThat(redacted.characterCount()).isNotEqualTo(original.characterCount());
    assertThat(redacted.characterCount()).isEqualTo(redacted.content().length());
  }

  private static ContextItem item(String label, String content) {
    return new ContextItem(
        "item-1",
        ContextKind.DECISION,
        label,
        content,
        new ContextProvenance(
            ContextSource.versioned(ContextSourceType.BRAIN_ENTRY, "entry-1", 2),
            PROJECT,
            OBSERVED_AT));
  }
}
