package com.vibecode.context.application.policy;

import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextPolicyVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The rules that decide what may enter a pack, and the deny that answers when none of them do.
 *
 * <p><b>Deny by default, and the default is not silence.</b> An item enters only if a rule says
 * ALLOW. No rule speaking is a denial, and it is returned as an explicit {@link ContextAdmission}
 * naming {@link #DEFAULT_DENY_RULE_ID}, not as an empty result a caller could read as "nothing
 * objected". "No risk was found, so it goes in" is the failure mode this whole engine exists to
 * prevent: the engine assembles what a model will be shown, and a thing nobody decided to include
 * is a thing nobody decided not to include either.
 *
 * <p><b>Evaluation is deterministic.</b> Rules are sorted by {@link
 * ContextPolicyRule#evaluationRank()} and then by {@link ContextPolicyRule#ruleId()}, so the order
 * does not depend on how a list was written or how Spring happened to inject anything. The first
 * rule to speak decides; later rules are not consulted. Duplicate rule ids are rejected at
 * construction, because two rules sharing an id make a stored pack's rule reference ambiguous —
 * which is the one thing the id exists to prevent.
 *
 * <p><b>This class selects context. It does not decide security.</b> Admitting a security summary
 * item puts the posture in front of the work; it resolves nothing, closes no finding and satisfies
 * no gate. A CRITICAL finding remains the Security Gate's business before and after a pack is
 * compiled, and no rule here may be written to change that.
 *
 * <p>The version is carried, not derived. It is stamped onto every pack this policy compiles so an
 * old pack can be explained rather than guessed at — see {@link ContextPolicyVersion}.
 */
public final class ContextPolicy {

  /**
   * The rule id recorded when no rule spoke. Reserved: no declared rule may claim it, and the
   * constructor enforces that.
   *
   * <p>It is a real, greppable handle rather than a null, so that "denied because nothing admitted
   * it" is as traceable in a log or a report as any considered refusal.
   */
  public static final String DEFAULT_DENY_RULE_ID = "context.policy.default-deny";

  private static final String DEFAULT_DENY_EXPLANATION =
      "No policy rule admits this item. Context is deny-by-default: an item enters a pack only"
          + " because a rule says it may, never because nothing objected.";

  private final ContextPolicyVersion version;
  private final List<ContextPolicyRule> rules;

  /**
   * @param version the version stamped on every pack this policy compiles
   * @param rules the declared rules, in any order; sorted here
   */
  public ContextPolicy(ContextPolicyVersion version, List<ContextPolicyRule> rules) {
    if (version == null) {
      throw new IllegalArgumentException("A policy must declare its version");
    }
    if (rules == null) {
      throw new IllegalArgumentException("A policy must be given its rules, even if none");
    }
    List<ContextPolicyRule> sorted = new ArrayList<>(rules.size());
    Set<String> seenIds = new HashSet<>();
    for (ContextPolicyRule rule : rules) {
      if (rule == null) {
        throw new IllegalArgumentException("A policy cannot hold a null rule");
      }
      if (DEFAULT_DENY_RULE_ID.equals(rule.ruleId())) {
        throw new IllegalArgumentException(
            "Rule id "
                + DEFAULT_DENY_RULE_ID
                + " is reserved for the deny that answers when no rule spoke; a declared rule using"
                + " it would make a considered decision indistinguishable from the absence of one");
      }
      if (!seenIds.add(rule.ruleId())) {
        throw new IllegalArgumentException(
            "Two rules share the id " + rule.ruleId() + ", so a stored pack could not say which"
                + " one admitted an item");
      }
      sorted.add(rule);
    }
    sorted.sort(
        Comparator.comparingInt(ContextPolicyRule::evaluationRank)
            .thenComparing(ContextPolicyRule::ruleId));
    this.version = version;
    this.rules = List.copyOf(sorted);
  }

  /** The policy in force, at {@link ContextPolicyVersion#CURRENT}. */
  public static ContextPolicy current() {
    return new ContextPolicy(ContextPolicyVersion.CURRENT, DefaultContextPolicyRules.rules());
  }

  public ContextPolicyVersion version() {
    return version;
  }

  /** The rules, in the order they are evaluated. Unmodifiable. */
  public List<ContextPolicyRule> rules() {
    return rules;
  }

  /**
   * The decision about one candidate. Never null, and never an allow without a rule id and an
   * explanation — {@link ContextAdmission} refuses to be built without both.
   */
  public ContextAdmission admit(ContextItem item) {
    if (item == null) {
      throw new IllegalArgumentException("A policy was asked to decide about nothing");
    }
    for (ContextPolicyRule rule : rules) {
      Optional<ContextAdmission> decided = rule.evaluate(item);
      if (decided.isPresent()) {
        return decided.get();
      }
    }
    return ContextAdmission.deny(DEFAULT_DENY_RULE_ID, DEFAULT_DENY_EXPLANATION);
  }
}
