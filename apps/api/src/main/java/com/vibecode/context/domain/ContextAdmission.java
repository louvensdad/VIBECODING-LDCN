package com.vibecode.context.domain;

/**
 * The recorded decision about one candidate item: what was decided, under which rule, and why.
 *
 * <p><b>All three fields are mandatory, including on a denial.</b> An {@link
 * ContextAdmissionDecision#ALLOW} without a rule id would be an item somebody let in with nothing
 * to point at afterwards, and an allow without an explanation would be a rule id nobody outside the
 * code can read. A denial is held to the same standard so that "why is this not here?" has an
 * answer of the same quality as "why is this here?" — including the deny-by-default case, which
 * names a reserved rule rather than leaving the field empty.
 *
 * <p>{@code policyRuleId} is the load-bearing half. It is a stable machine handle: the Inspector
 * can show a reason that traces back to a named rule a reader can look up and disagree with.
 * {@code explanation} is prose derived from that rule, and is never the sole record of the
 * decision — prose alone is how one collector's improvisation ends up reading like policy.
 *
 * <p><b>An admission is a historical fact, not a live lookup.</b> Once a pack is written, its
 * items keep the rule id and explanation that were in force when it was compiled. Re-deriving them
 * from today's policy would quietly rewrite the record — a pack compiled under policy version 1
 * must keep saying what version 1 said, even after version 2 exists. See {@code
 * ContextPolicyVersion}.
 *
 * @param decision whether the item may enter a pack
 * @param policyRuleId the stable id of the rule that decided; a handle, never prose
 * @param explanation one sentence a person can read, derived from that rule
 */
public record ContextAdmission(
    ContextAdmissionDecision decision, String policyRuleId, String explanation) {

  public ContextAdmission {
    if (decision == null) {
      throw new IllegalArgumentException("An admission must state its decision");
    }
    if (policyRuleId == null || policyRuleId.isBlank()) {
      throw new IllegalArgumentException(
          "An admission must name the policy rule that decided it: a reason with no rule behind it"
              + " cannot be looked up or disagreed with");
    }
    if (explanation == null || explanation.isBlank()) {
      throw new IllegalArgumentException(
          "An admission must explain itself: rule "
              + policyRuleId
              + " produced a decision no reader could act on");
    }
  }

  /** An allow, which is the only decision that puts an item in front of anyone. */
  public static ContextAdmission allow(String policyRuleId, String explanation) {
    return new ContextAdmission(ContextAdmissionDecision.ALLOW, policyRuleId, explanation);
  }

  /** A denial. Held to exactly the same standard of evidence as an allow. */
  public static ContextAdmission deny(String policyRuleId, String explanation) {
    return new ContextAdmission(ContextAdmissionDecision.DENY, policyRuleId, explanation);
  }

  public boolean isAllowed() {
    return decision == ContextAdmissionDecision.ALLOW;
  }
}
