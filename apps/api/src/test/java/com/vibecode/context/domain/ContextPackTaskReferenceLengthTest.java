package com.vibecode.context.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.context.application.redaction.ContextRedaction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The same boundary as {@code ContextItemLabelLengthTest}, for the pack's own free-text field.
 *
 * <p>{@code context_packs.task_reference} is {@code VARCHAR(500)} in V9 and was capped nowhere
 * else. It shares the label's mechanism exactly: it is composed by a caller from user text — V9
 * sizes the column for {@code "TASK-42: "} in front of a task title — and it passes through
 * {@code ContextRedaction.redactTaskReference}, which lengthens it whenever a matched value is
 * shorter than {@code [REDACTED]}. A 498-character reference measured 507 after redaction and
 * failed at the INSERT.
 *
 * <p>The cap lives on {@link ContextPack} rather than on {@link CompiledContextPack} because the
 * compiled form builds a {@code ContextPack} in its own constructor, so one check covers both and
 * there is no second definition to drift.
 *
 * <p>All fixtures are synthetic.
 */
class ContextPackTaskReferenceLengthTest {

  private static final UUID PACK = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final Instant OBSERVED = Instant.parse("2026-02-02T10:00:00Z");
  private static final ContextBudget BUDGET = new ContextBudget(50, 100_000L, 200_000L);

  private static final ContextItem ITEM =
      new ContextItem(
          "i-1",
          ContextKind.DECISION,
          "A label",
          "synthetic content",
          new ContextProvenance(
              ContextSource.of(ContextSourceType.BRAIN_ENTRY, "dec-1"), PROJECT, OBSERVED));

  private static ContextPack packFor(String taskReference) {
    return new ContextPack(PACK, PROJECT, taskReference, OBSERVED, BUDGET, List.of(ITEM));
  }

  private static CompiledContextPack compiledFor(String taskReference) {
    return new CompiledContextPack(
        PACK,
        PROJECT,
        taskReference,
        OBSERVED,
        BUDGET,
        ContextPolicyVersion.CURRENT,
        List.of(
            new AdmittedContextItem(
                ContextRedaction.redact(ITEM),
                ContextAdmission.allow("context.policy.test", "Fixture admission."))));
  }

  @Test
  @DisplayName("499 and 500 — the boundary itself — are both accepted")
  void referencesUpToTheCapAreAccepted() {
    assertThatCode(() -> packFor("T".repeat(499))).doesNotThrowAnyException();
    assertThatCode(() -> packFor("T".repeat(ContextPack.MAX_TASK_REFERENCE_LENGTH)))
        .doesNotThrowAnyException();

    assertThat(packFor("T".repeat(500)).taskReference()).hasSize(500);
  }

  @Test
  @DisplayName("501 is refused at construction, by the compiled form as well as the bare pack")
  void referencesOverTheCapAreRefusedWhereTheyAreMade() {
    assertThatThrownBy(() -> packFor("T".repeat(501)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("may not exceed 500")
        .hasMessageContaining("has 501");

    // The compiled form is the one the compiler actually builds and the one persistence writes, so
    // it is asserted separately rather than assumed to inherit the check.
    assertThatThrownBy(() -> compiledFor("T".repeat(501)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("may not exceed 500");
  }

  @Test
  @DisplayName("Redaction lengthens a 498-character reference to 507, and it is refused there")
  void redactionLengtheningIsRefusedBeforeAPackExists() {
    String underTheCapBeforeRedaction = "T".repeat(490) + " TOKEN=x";
    assertThat(underTheCapBeforeRedaction).hasSize(498);

    // redactTaskReference returns a String rather than building anything, so the growth is visible
    // here and the refusal happens at the next step — where the compiler hands the redacted value
    // to CompiledContextPack. That is still before any row is composed.
    String redacted = ContextRedaction.redactTaskReference(underTheCapBeforeRedaction);
    assertThat(redacted).hasSize(507).endsWith("TOKEN=[REDACTED]");

    assertThatThrownBy(() -> compiledFor(redacted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("may not exceed 500")
        .hasMessageContaining("has 507");
  }

  @Test
  @DisplayName("The cap counts UTF-16 code units, as the label's does and as the budget does")
  void theCapIsMeasuredInCodeUnits() {
    String atTheCap = "😀".repeat(250);
    assertThat(atTheCap).hasSize(500);
    assertThat(atTheCap.codePointCount(0, atTheCap.length())).isEqualTo(250);
    assertThatCode(() -> packFor(atTheCap)).doesNotThrowAnyException();

    assertThatThrownBy(() -> packFor("😀".repeat(251)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UTF-16 code units");
  }
}
