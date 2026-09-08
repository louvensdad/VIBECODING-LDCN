package com.vibecode.context.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextAdmissionDecision;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextPolicyVersion;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deny by default, and the rest of what a policy must not be able to do.
 *
 * <p>The first test here is the one the whole engine rests on. Everything else - redaction,
 * budgets, digests - operates on items that policy already let through, so a policy that leaked
 * into allow-by-default would make every downstream guarantee true and irrelevant.
 */
class ContextPolicyTest {

  private static final UUID PROJECT = UUID.randomUUID();
  private static final Instant OBSERVED_AT = Instant.parse("2026-03-01T10:15:30Z");

  @Test
  @DisplayName("An item no rule speaks about is denied, under a named rule")
  void absenceOfARuleIsADenial() {
    // An empty policy is the purest statement of the property: nothing declared, so nothing may
    // enter. A policy that answered "allow" here would be answering "nobody objected", which is
    // not the same thing as "somebody decided".
    ContextPolicy empty = new ContextPolicy(ContextPolicyVersion.CURRENT, List.of());

    ContextAdmission decision =
        empty.admit(item("anything", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1"));

    assertThat(decision.decision()).isEqualTo(ContextAdmissionDecision.DENY);
    assertThat(decision.isAllowed()).isFalse();
    // Traceable even here: the default deny names a reserved rule rather than leaving the field
    // empty, so "denied because nothing admitted it" can be grepped like any other decision.
    assertThat(decision.policyRuleId()).isEqualTo(ContextPolicy.DEFAULT_DENY_RULE_ID);
    assertThat(decision.explanation()).contains("deny-by-default");
  }

  @Test
  @DisplayName("The policy in force denies every source it does not name")
  void theCurrentPolicyDeniesWhatItDoesNotName() {
    ContextPolicy policy = ContextPolicy.current();

    // The roadmap as a whole is collected and is deliberately not admitted: the phase in progress
    // and the current task already carry the part of the plan that bears on the work.
    ContextAdmission roadmap =
        policy.admit(item("roadmap-1", ContextKind.OBJECTIVE, ContextSourceType.ROADMAP, "rm-1"));
    assertThat(roadmap.policyRuleId()).isEqualTo(ContextPolicy.DEFAULT_DENY_RULE_ID);

    // A remembered completed step is not admitted either: the computed state says where things
    // actually stand, and a remembered claim about the same thing would compete with it.
    ContextAdmission remembered =
        policy.admit(
            item("brain-1", ContextKind.COMPLETED_STEP, ContextSourceType.BRAIN_ENTRY, "entry-1"));
    assertThat(remembered.isAllowed()).isFalse();
    assertThat(remembered.policyRuleId()).isEqualTo(ContextPolicy.DEFAULT_DENY_RULE_ID);
  }

  @Test
  @DisplayName("Every allow the policy can produce carries a rule id and an explanation")
  void everyAllowIsAccountedFor() {
    ContextPolicy policy = ContextPolicy.current();

    // Across the whole cross product of sources and kinds, not just the ones a fixture happens to
    // produce: an allow that escaped without one of the two halves would be reachable from some
    // combination, and this is the only way to know it is not.
    for (ContextSourceType sourceType : ContextSourceType.values()) {
      for (ContextKind kind : ContextKind.values()) {
        ContextAdmission decision = policy.admit(item("probe", kind, sourceType, "source-1"));
        assertThat(decision.policyRuleId()).isNotBlank();
        assertThat(decision.explanation()).isNotBlank();
        if (decision.isAllowed()) {
          assertThat(decision.policyRuleId()).isNotEqualTo(ContextPolicy.DEFAULT_DENY_RULE_ID);
          assertThat(policy.rules())
              .extracting(ContextPolicyRule::ruleId)
              .contains(decision.policyRuleId());
        }
      }
    }
  }

  @Test
  @DisplayName("A refusal by name is distinguishable from never having been considered")
  void anExplicitDenialNamesItsRule() {
    ContextPolicy policy = ContextPolicy.current();

    ContextAdmission modelOutput =
        policy.admit(
            item("brain-2", ContextKind.PROMPT_RESULT, ContextSourceType.BRAIN_ENTRY, "entry-2"));

    assertThat(modelOutput.isAllowed()).isFalse();
    assertThat(modelOutput.policyRuleId()).isEqualTo("context.policy.deny-model-output");
    assertThat(modelOutput.policyRuleId()).isNotEqualTo(ContextPolicy.DEFAULT_DENY_RULE_ID);
  }

  @Test
  @DisplayName("Evaluation order is fixed by rank, not by the order rules were handed over")
  void evaluationOrderIsDeterministic() {
    ContextPolicyRule denyEarly =
        ContextSelectionRule.denyingKinds(
            "test.deny-first", 10, "Refused first.", Set.of(ContextKind.RULE));
    ContextPolicyRule allowLate =
        ContextSelectionRule.allowingSources(
            "test.allow-later", 20, "Admitted later.", Set.of(ContextSourceType.BRAIN_ENTRY));

    ContextItem subject = item("x", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1");

    // Both orderings of the same two rules, ten times over a shuffled list: the answer must be the
    // refusal every time, because rank decides and the caller's list order does not.
    List<ContextPolicyRule> declared = new ArrayList<>(List.of(allowLate, denyEarly));
    Random shuffleSeed = new Random(20260301L);
    for (int attempt = 0; attempt < 10; attempt++) {
      java.util.Collections.shuffle(declared, shuffleSeed);
      ContextPolicy policy = new ContextPolicy(ContextPolicyVersion.CURRENT, declared);

      assertThat(policy.rules())
          .extracting(ContextPolicyRule::ruleId)
          .containsExactly("test.deny-first", "test.allow-later");
      assertThat(policy.admit(subject).policyRuleId()).isEqualTo("test.deny-first");
      assertThat(policy.admit(subject).isAllowed()).isFalse();
    }
  }

  @Test
  @DisplayName("Rules sharing a rank are still ordered, by id")
  void tiesAreBrokenByRuleId() {
    ContextPolicyRule beta =
        ContextSelectionRule.allowingSources(
            "test.b", 10, "Second alphabetically.", Set.of(ContextSourceType.BRAIN_ENTRY));
    ContextPolicyRule alpha =
        ContextSelectionRule.denyingKinds(
            "test.a", 10, "First alphabetically.", Set.of(ContextKind.RULE));

    ContextPolicy policy = new ContextPolicy(ContextPolicyVersion.CURRENT, List.of(beta, alpha));

    assertThat(policy.rules()).extracting(ContextPolicyRule::ruleId).containsExactly("test.a", "test.b");
  }

  @Test
  @DisplayName("Two rules cannot share an id, because a stored pack could not say which one decided")
  void duplicateRuleIdsAreRejected() {
    ContextPolicyRule one =
        ContextSelectionRule.allowingSources(
            "test.same", 10, "One.", Set.of(ContextSourceType.BRAIN_ENTRY));
    ContextPolicyRule two =
        ContextSelectionRule.allowingSources(
            "test.same", 20, "Two.", Set.of(ContextSourceType.PROJECT));

    assertThatThrownBy(() -> new ContextPolicy(ContextPolicyVersion.CURRENT, List.of(one, two)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("share the id");
  }

  @Test
  @DisplayName("A declared rule cannot claim the reserved default-deny id")
  void theDefaultDenyIdIsReserved() {
    ContextPolicyRule impostor =
        ContextSelectionRule.allowingSources(
            ContextPolicy.DEFAULT_DENY_RULE_ID,
            10,
            "Pretending to be the default.",
            Set.of(ContextSourceType.PROJECT));

    assertThatThrownBy(() -> new ContextPolicy(ContextPolicyVersion.CURRENT, List.of(impostor)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved");
  }

  @Test
  @DisplayName("A rule that applies to everything is rejected: that is a default, not a rule")
  void aRuleMustApplyToSomethingInParticular() {
    assertThatThrownBy(
            () ->
                new ContextSelectionRule(
                    "test.everything",
                    10,
                    ContextAdmissionDecision.ALLOW,
                    "Everything, apparently.",
                    Set.of(),
                    Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("applies to everything");
  }

  @Test
  @DisplayName("A rule cannot be declared without an explanation")
  void aRuleMustExplainItself() {
    assertThatThrownBy(
            () ->
                ContextSelectionRule.allowingSources(
                    "test.silent", 10, "  ", Set.of(ContextSourceType.PROJECT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must explain itself");
  }

  @Test
  @DisplayName("Every declared rule of the policy in force is complete and uniquely ranked")
  void thePolicyInForceIsWellFormed() {
    ContextPolicy policy = ContextPolicy.current();

    assertThat(policy.version()).isEqualTo(ContextPolicyVersion.CURRENT);
    assertThat(policy.rules()).isNotEmpty();
    assertThat(policy.rules()).extracting(ContextPolicyRule::ruleId).doesNotHaveDuplicates();
    // Distinct ranks are not required by the engine - it breaks ties by id - but two rules sharing
    // one is almost always a copy-paste, and the order between them would then depend on a name.
    assertThat(policy.rules()).extracting(ContextPolicyRule::evaluationRank).doesNotHaveDuplicates();

    for (ContextPolicyRule rule : policy.rules()) {
      assertThat(rule.ruleId()).startsWith("context.policy.");
      assertThat(rule.ruleId()).isNotEqualTo(ContextPolicy.DEFAULT_DENY_RULE_ID);
    }
  }

  @Test
  @DisplayName("A rule that does not apply says nothing, which is not an allow")
  void notApplyingIsNotApproving() {
    ContextPolicyRule taskOnly =
        ContextSelectionRule.allowingSources(
            "test.task-only", 10, "Only the task.", Set.of(ContextSourceType.CURRENT_TASK));

    Optional<ContextAdmission> silence =
        taskOnly.evaluate(item("x", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1"));

    assertThat(silence).isEmpty();
  }

  private static ContextItem item(
      String id, ContextKind kind, ContextSourceType sourceType, String sourceId) {
    return new ContextItem(
        id,
        kind,
        "Label for " + id,
        "Synthetic content for " + id,
        new ContextProvenance(ContextSource.of(sourceType, sourceId), PROJECT, OBSERVED_AT));
  }
}
