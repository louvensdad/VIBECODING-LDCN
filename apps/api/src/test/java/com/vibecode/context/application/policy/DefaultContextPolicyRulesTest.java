package com.vibecode.context.application.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextDigest;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextPolicyVersion;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule set, pinned to the version it is stamped with.
 *
 * <p>{@code context_packs.policy_version} exists so a reader comparing an old pack with a new one
 * can see whether the rules moved. That column is worth nothing unless the version actually moves
 * when the rules do, and until this test existed nothing made it. A rule could gain a kind - a
 * genuine change to what every pack contains - and the whole suite stayed green while two
 * materially different policies both stamped {@code "1"}. A reader would then be told the rules did
 * not move when they did, which is worse than not recording the version at all.
 *
 * <p>So this digests the declared rule set and compares it to a recorded constant. The lock runs in
 * both directions: edit a rule and the digest stops matching, bump the version and the version
 * assertion stops matching. Either way somebody has to come here and answer the question
 * deliberately.
 *
 * <p><b>What to do when this fails.</b> Not to paste the new digest in. Decide first whether the
 * edit changes what a pack contains. If it does - a new rule, a removed one, a widened kind set, a
 * decision flipped - bump {@link ContextPolicyVersion#CURRENT} and update <em>both</em> constants
 * below. If it genuinely does not, which in practice means only a reworded explanation, the version
 * may stay and only the digest moves. An explanation is inside the pack digest and survives into
 * every stored row, so even a rewording is a visible change; it is just not a change to what is
 * admitted.
 *
 * <p>This is not covered by {@code ContextPackCompilerTest.denyByDefaultHoldsEndToEnd}. That test
 * lists kinds that must not appear, which catches a widening onto one of those kinds and nothing
 * else. It is an enumeration of known-bad, not a golden record of what is admitted.
 */
class DefaultContextPolicyRulesTest {

  /** Field separator, matching the pack payload. A control character, written as one. */
  private static final char SEPARATOR = (char) 0x1F;

  /** The version the rule set below is recorded against. Moves only with a deliberate decision. */
  private static final String PINNED_VERSION = "1";

  /**
   * SHA-256 over the declared rules: for each, in evaluation order, its id, rank, decision, the
   * sources and kinds it covers, and its explanation.
   */
  private static final String PINNED_RULE_SET_DIGEST =
      "cb8c1538537688f21f298aa1d9b1dc6bc5e7522fde0b81961b592f03ada2d2ad";

  @Test
  @DisplayName("The declared rule set is exactly the one the current policy version stands for")
  void theRuleSetIsPinnedToItsPolicyVersion() {
    assertThat(ContextPolicyVersion.CURRENT.value())
        .as(
            "the recorded rule-set digest below belongs to policy version %s; if the version has"
                + " moved, the digest recorded with it must move too",
            PINNED_VERSION)
        .isEqualTo(PINNED_VERSION);

    assertThat(digestOfDeclaredRules())
        .as(
            "the rules changed while ContextPolicyVersion.CURRENT stayed at %s, so two different"
                + " policies would stamp the same version and no reader could tell an old pack's"
                + " rules from today's. Decide whether the change alters what a pack contains: if"
                + " it does, bump CURRENT and update both constants in this test.",
            PINNED_VERSION)
        .isEqualTo(PINNED_RULE_SET_DIGEST);
  }

  @Test
  @DisplayName("Every declared rule is stated as data, so the whole rule set can be digested")
  void everyRuleIsInspectable() {
    // The digest above reads the sources, kinds and decision off each rule. A rule that carried a
    // predicate instead would be undigestable, and this test would silently start covering less
    // than it claims to - so the shape is asserted rather than assumed.
    for (ContextPolicyRule rule : ContextPolicy.current().rules()) {
      assertThat(rule)
          .as("rule %s must be declarative for the rule set to be pinnable", rule.ruleId())
          .isInstanceOf(ContextSelectionRule.class);
    }
  }

  /**
   * The declared rules in evaluation order, flattened.
   *
   * <p>Length-prefixed for the same reason the pack payload is: an explanation is prose and could
   * otherwise forge a field boundary. Enum sets are sorted by name so the digest does not depend on
   * the iteration order of a {@code Set}.
   */
  private static String digestOfDeclaredRules() {
    StringBuilder canonical = new StringBuilder();
    for (ContextPolicyRule rule : ContextPolicy.current().rules()) {
      ContextSelectionRule declared = (ContextSelectionRule) rule;
      append(canonical, declared.ruleId());
      append(canonical, Integer.toString(declared.evaluationRank()));
      append(canonical, declared.decision().name());
      append(canonical, String.join(",", sortedNames(declared.sourceTypes())));
      append(canonical, String.join(",", sortedKindNames(declared.kinds())));
      append(canonical, declared.explanation());
    }
    return ContextDigest.sha256Hex(canonical.toString());
  }

  private static List<String> sortedNames(java.util.Set<ContextSourceType> sourceTypes) {
    return sourceTypes.stream().map(Enum::name).sorted().toList();
  }

  private static List<String> sortedKindNames(java.util.Set<ContextKind> kinds) {
    return kinds.stream().map(Enum::name).sorted().toList();
  }

  private static void append(StringBuilder canonical, String field) {
    canonical.append(field.length()).append(SEPARATOR).append(field).append(SEPARATOR);
  }
}
