package com.vibecode.context.application.policy;

import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextAdmissionDecision;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * A policy rule stated as data: which sources and kinds it covers, what it decides, and why.
 *
 * <p><b>Declarative rather than a predicate.</b> A rule carrying a lambda would be just as small
 * and just as testable, and would still be unreadable to anything but a human running the code —
 * "what does this policy admit?" could only be answered by feeding it items one at a time. Stated
 * as two enum sets, a rule can be listed, printed, diffed between policy versions and checked for
 * overlap with its neighbours. That is what makes {@link ContextPolicy} a policy rather than a
 * program.
 *
 * <p>An empty set means "any". Both sets empty is rejected: a rule that applies to everything is
 * not a rule, it is a default, and the default belongs to {@link ContextPolicy} under its own
 * reserved id where nobody can mistake it for a considered decision.
 *
 * @param ruleId stable id, persisted with every item this rule admits
 * @param evaluationRank position in the evaluation order; lower is asked first
 * @param decision what this rule says about the items it covers
 * @param explanation one sentence, written for the person reading the Inspector
 * @param sourceTypes the sources covered, or empty for any
 * @param kinds the kinds covered, or empty for any
 */
public record ContextSelectionRule(
    String ruleId,
    int evaluationRank,
    ContextAdmissionDecision decision,
    String explanation,
    Set<ContextSourceType> sourceTypes,
    Set<ContextKind> kinds)
    implements ContextPolicyRule {

  public ContextSelectionRule {
    if (ruleId == null || ruleId.isBlank()) {
      throw new IllegalArgumentException("A policy rule must have an id");
    }
    if (decision == null) {
      throw new IllegalArgumentException("Rule " + ruleId + " must state what it decides");
    }
    if (explanation == null || explanation.isBlank()) {
      throw new IllegalArgumentException(
          "Rule "
              + ruleId
              + " must explain itself: the explanation is what a reader of a stored pack gets, and"
              + " an id alone tells them nothing");
    }
    if (sourceTypes == null || kinds == null) {
      throw new IllegalArgumentException("Rule " + ruleId + " must state what it applies to");
    }
    if (sourceTypes.isEmpty() && kinds.isEmpty()) {
      throw new IllegalArgumentException(
          "Rule "
              + ruleId
              + " applies to everything, which makes it a default rather than a rule. The default"
              + " is deny, and it belongs to ContextPolicy under its own reserved id.");
    }
    sourceTypes =
        sourceTypes.isEmpty()
            ? Set.of()
            : Set.copyOf(EnumSet.copyOf(sourceTypes));
    kinds = kinds.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(kinds));
  }

  /** An allow covering whole sources, whatever kinds they produce. */
  public static ContextSelectionRule allowingSources(
      String ruleId, int rank, String explanation, Set<ContextSourceType> sourceTypes) {
    return new ContextSelectionRule(
        ruleId, rank, ContextAdmissionDecision.ALLOW, explanation, sourceTypes, Set.of());
  }

  /** An allow narrowed to particular kinds within particular sources. */
  public static ContextSelectionRule allowing(
      String ruleId,
      int rank,
      String explanation,
      Set<ContextSourceType> sourceTypes,
      Set<ContextKind> kinds) {
    return new ContextSelectionRule(
        ruleId, rank, ContextAdmissionDecision.ALLOW, explanation, sourceTypes, kinds);
  }

  /** A denial stated out loud, so that "considered and refused" is distinguishable from "never
   * considered". Both end in the item staying out; only one of them is a decision. */
  public static ContextSelectionRule denyingKinds(
      String ruleId, int rank, String explanation, Set<ContextKind> kinds) {
    return new ContextSelectionRule(
        ruleId, rank, ContextAdmissionDecision.DENY, explanation, Set.of(), kinds);
  }

  @Override
  public Optional<ContextAdmission> evaluate(ContextItem item) {
    if (item == null) {
      throw new IllegalArgumentException("Rule " + ruleId + " was asked about nothing");
    }
    boolean sourceMatches =
        sourceTypes.isEmpty() || sourceTypes.contains(item.provenance().sourceType());
    boolean kindMatches = kinds.isEmpty() || kinds.contains(item.kind());
    if (!sourceMatches || !kindMatches) {
      return Optional.empty();
    }
    return Optional.of(new ContextAdmission(decision, ruleId, explanation));
  }
}
