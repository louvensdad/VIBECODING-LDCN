package com.vibecode.context.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.context.application.redaction.ContextRedaction;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two halves of a reason, and why neither is optional.
 *
 * <p>An allow with no rule id is an item somebody let in with nothing to point at afterwards. An
 * allow with no explanation is a rule id that means nothing to the person reading the Inspector.
 * Both are enforced by the constructor rather than by a caller remembering, which is what these
 * tests hold in place.
 */
class ContextAdmissionTest {

  private static final UUID PROJECT = UUID.randomUUID();

  @Test
  @DisplayName("An allow without a rule id cannot be built")
  void allowRequiresARuleId() {
    assertThatThrownBy(() -> ContextAdmission.allow(null, "Because it is useful."))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must name the policy rule");

    assertThatThrownBy(() -> ContextAdmission.allow("  ", "Because it is useful."))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must name the policy rule");
  }

  @Test
  @DisplayName("An allow without an explanation cannot be built")
  void allowRequiresAnExplanation() {
    assertThatThrownBy(() -> ContextAdmission.allow("context.policy.example", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must explain itself");

    assertThatThrownBy(() -> ContextAdmission.allow("context.policy.example", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must explain itself");
  }

  @Test
  @DisplayName("A denial is held to exactly the same standard as an allow")
  void denialRequiresBothToo() {
    // Otherwise "why is this not here?" would be answerable to a lower standard than "why is this
    // here?", and the deny-by-default case - the one that happens most - would be the vaguest.
    assertThatThrownBy(() -> ContextAdmission.deny(null, "Because it is noise."))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ContextAdmission.deny("context.policy.example", " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("An item cannot be materialised without an admission")
  void materialisationRequiresAnAdmission() {
    assertThatThrownBy(() -> new AdmittedContextItem(ContextRedaction.redact(item("solo")), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be materialised without an admission");
  }

  @Test
  @DisplayName("A denied item cannot be materialised, even by a caller who has one in hand")
  void deniedItemsCannotBeMaterialised() {
    ContextAdmission denial =
        ContextAdmission.deny("context.policy.deny-model-output", "Not project state.");

    assertThatThrownBy(() -> new AdmittedContextItem(ContextRedaction.redact(item("denied")), denial))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("was denied by rule")
        .hasMessageContaining("content is not stored so it can be shown later");
  }

  @Test
  @DisplayName("An allow carries both halves through to the item that holds it")
  void anAllowSurvivesOntoTheItem() {
    ContextAdmission allow = ContextAdmission.allow("context.policy.current-task", "The task.");
    AdmittedContextItem admitted = new AdmittedContextItem(ContextRedaction.redact(item("kept")), allow);

    assertThat(admitted.admission().isAllowed()).isTrue();
    assertThat(admitted.admission().policyRuleId()).isEqualTo("context.policy.current-task");
    assertThat(admitted.admission().explanation()).isEqualTo("The task.");
    assertThat(admitted.id()).isEqualTo("kept");
  }

  private static ContextItem item(String id) {
    return new ContextItem(
        id,
        ContextKind.OBJECTIVE,
        "Label for " + id,
        "Synthetic content for " + id,
        new ContextProvenance(
            ContextSource.of(ContextSourceType.CURRENT_TASK, "task-1"),
            PROJECT,
            Instant.parse("2026-03-01T10:15:30Z")));
  }
}
