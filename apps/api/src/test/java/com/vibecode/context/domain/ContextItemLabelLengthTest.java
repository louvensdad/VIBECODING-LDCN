package com.vibecode.context.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.context.application.redaction.ContextRedaction;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where an over-long label is refused, and in what unit it is measured.
 *
 * <p>Before {@link ContextItem#MAX_LABEL_LENGTH} existed, the only cap was the width of
 * {@code context_pack_items.label} in V9, and nothing asserted it anywhere. A 501-character label
 * constructed cleanly, passed policy, redaction, budgeting and selection, and then failed at the
 * INSERT with {@code DataIntegrityViolationException: Value too long for column "label CHARACTER
 * VARYING(500)"} — the last place in the pipeline where the cause is legible. These tests pin the
 * failure to construction instead.
 *
 * <p>The boundary is tested at 499, 500 and 501 rather than at one of the three, because the two
 * interesting mistakes — off by one in either direction — are each invisible from the other two
 * points.
 *
 * <p>All fixtures are synthetic. The one that looks like a credential is a made-up value chosen
 * because the redactor's key/value pattern matches its shape; nothing here is real.
 */
class ContextItemLabelLengthTest {

  private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final Instant OBSERVED = Instant.parse("2026-02-02T10:00:00Z");

  private static ContextItem itemLabelled(String label) {
    return new ContextItem(
        "i-label-boundary",
        ContextKind.DECISION,
        label,
        "synthetic content",
        new ContextProvenance(
            ContextSource.of(ContextSourceType.BRAIN_ENTRY, "dec-1"), PROJECT, OBSERVED));
  }

  @Test
  @DisplayName("The cap is the number V9 was written with, checked against the schema elsewhere")
  void theCapMatchesTheColumn() {
    // A retyped literal, and it is worth being clear about what that is worth: on its own it only
    // says the constant has not been changed by accident. The check that actually compares the
    // constant with the schema Flyway applied is theCapsMatchTheAppliedSchema in
    // ContextPackTextWidthBoundaryTest, which reads the width out of INFORMATION_SCHEMA. Neither
    // catches a migration and a constant edited together in one commit; that pair agrees, which is
    // the correct outcome, so no test here can distinguish an intended widening from a careless one.
    assertThat(ContextItem.MAX_LABEL_LENGTH).isEqualTo(500);
  }

  @Test
  @DisplayName("499 characters is accepted, and 500 — the boundary itself — is accepted too")
  void labelsUpToTheCapAreAccepted() {
    assertThatCode(() -> itemLabelled("L".repeat(499))).doesNotThrowAnyException();
    assertThatCode(() -> itemLabelled("L".repeat(ContextItem.MAX_LABEL_LENGTH)))
        .doesNotThrowAnyException();

    assertThat(itemLabelled("L".repeat(500)).label()).hasSize(500);
  }

  @Test
  @DisplayName("501 characters is refused at construction, not at the INSERT")
  void labelsOverTheCapAreRefusedWhereTheyAreMade() {
    assertThatThrownBy(() -> itemLabelled("L".repeat(501)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("may not exceed 500")
        // The item's id is in the message because "some label is too long" is not actionable in a
        // pack of fifty items.
        .hasMessageContaining("i-label-boundary")
        .hasMessageContaining("has 501");
  }

  @Test
  @DisplayName("The cap counts UTF-16 code units, the same unit the budget counts")
  void theCapIsMeasuredInCodeUnits() {
    // 250 emoji: 500 UTF-16 code units, 250 code points, 1000 UTF-8 bytes. Accepted, because the
    // unit is the one ContextBudget and characterCount() already use — "😀" is 2, not 1.
    String atTheCap = "😀".repeat(250);
    assertThat(atTheCap).hasSize(500);
    assertThat(atTheCap.codePointCount(0, atTheCap.length())).isEqualTo(250);
    assertThatCode(() -> itemLabelled(atTheCap)).doesNotThrowAnyException();

    // One emoji further is 502 code units and is refused, even though 251 code points would still
    // be far under 500. Had this test been written against codePointCount it would have accepted a
    // label of 1000 code units, which H2 rejects outright and which no reader would expect from a
    // type whose javadoc says code units.
    String overTheCap = "😀".repeat(251);
    assertThat(overTheCap).hasSize(502);
    assertThatThrownBy(() -> itemLabelled(overTheCap))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UTF-16 code units");

    // The two engines disagree about what VARCHAR(500) counts: PostgreSQL 16 counts code points,
    // H2 2.3 counts UTF-16 code units. The cap picks the code-unit reading, which is the stricter
    // of the two — and this is the one string where being stricter is visible, so it is the one
    // worth asserting. 500 code points of supplementary-plane text is 1000 code units: a
    // code-point cap would wave it through and H2 would then reject the row.
    //
    // An earlier version of this test asserted codePointCount <= length here, which is true of
    // every Java String and passes with the cap deleted. It proved nothing. This one goes red the
    // moment the check is removed.
    String fiveHundredCodePoints = "😀".repeat(500);
    assertThat(fiveHundredCodePoints.codePointCount(0, fiveHundredCodePoints.length()))
        .isEqualTo(500);
    assertThat(fiveHundredCodePoints).hasSize(1000);
    assertThatThrownBy(() -> itemLabelled(fiveHundredCodePoints))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("has 1000");
  }

  @Test
  @DisplayName("Redaction can lengthen a label past the cap, and that now fails inside redaction")
  void redactionLengtheningIsCaughtAtRedactionRatherThanAtTheInsert() {
    // 498 characters, comfortably under the cap. The redactor replaces the one-character value
    // with the marker [REDACTED], and the label comes out at 507 — this is not hypothetical, it
    // was reproduced against the database before the cap existed and the INSERT was where it
    // surfaced.
    String underTheCapBeforeRedaction = "L".repeat(490) + " TOKEN=x";
    assertThat(underTheCapBeforeRedaction).hasSize(498);

    ContextItem item = itemLabelled(underTheCapBeforeRedaction);

    // The message names both lengths. Without that, a reader sees a 498-character label rejected
    // for exceeding 500 and concludes the check is broken; with it, the growth is the first thing
    // on the line. The domain's own prose is still underneath, as the cause.
    assertThatThrownBy(() -> ContextRedaction.redact(item))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Redaction lengthened item i-label-boundary")
        .hasMessageContaining("label 498 -> 507")
        .hasMessageContaining("may not exceed 500")
        .hasMessageContaining("has 507")
        // Kept as the cause rather than swallowed: the domain's exception is what actually decided,
        // and a stack trace that starts at the redactor with nothing under it hides that.
        .hasCauseInstanceOf(IllegalArgumentException.class);

    // The same label one character shorter still redacts to 506 — the point is not that 498 is a
    // magic number but that the gain is the marker's length minus the value's, so any label within
    // that distance of the cap is exposed. A label with room for the marker goes through
    // untouched by this rule.
    ContextItem comfortable = itemLabelled("L".repeat(100) + " TOKEN=x");
    assertThat(ContextRedaction.redact(comfortable).item().label()).contains("TOKEN=[REDACTED]");
  }
}
