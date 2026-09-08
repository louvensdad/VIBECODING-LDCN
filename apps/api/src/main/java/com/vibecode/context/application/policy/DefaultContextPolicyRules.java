package com.vibecode.context.application.policy;

import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import java.util.Set;

/**
 * The rules of {@link com.vibecode.context.domain.ContextPolicyVersion#CURRENT}, written out.
 *
 * <p><b>Minimum necessary context is the whole design of this list.</b> Available is not admitted.
 * The engine can read eleven sources and every one of them holds something true, but a pack is not
 * a report on the project — it is the smallest set of items that changes what the work in front of
 * the user should do. An item that would not change the answer is not free: it displaces one that
 * would, and it is one more thing a reader has to discount.
 *
 * <p>So the list below is short, and the sources it does <em>not</em> mention matter as much as the
 * ones it does. Nothing here mentions the roadmap: the phase in progress and the current task
 * already carry the slice of the plan that bears on the work, and the rest of the plan is a
 * document rather than context. Nothing here admits a brain entry recording a completed step, a
 * next step, a past error or its solution: the computed current state and the active errors source
 * say where things actually stand today, and remembered claims about the same thing would compete
 * with them. Those items are not refused by a rule — no rule speaks about them, and under
 * deny-by-default that is a denial. See {@link ContextPolicy}.
 *
 * <p>Two things <em>are</em> refused by name, because "considered and refused" is worth being able
 * to tell apart from "never considered".
 *
 * <p><b>Changing anything in this file means bumping {@link
 * com.vibecode.context.domain.ContextPolicyVersion#CURRENT}.</b> Packs already stored keep the rule
 * ids and explanations they were compiled with; the version is what lets a reader see that an old
 * pack and a new one were answering under different rules rather than from different state.
 */
public final class DefaultContextPolicyRules {

  private DefaultContextPolicyRules() {}

  /**
   * The declared rules, in an order that is readable rather than load-bearing — {@link
   * ContextPolicy} sorts them by rank before evaluating anything.
   *
   * <p>Ranks run in tens from 100, with the refusals first so that a refusal cannot be overtaken by
   * a broader allow declared later, and with gaps so a rule can be inserted between two others
   * without renumbering the file.
   */
  public static List<ContextPolicyRule> rules() {
    return List.of(
        ContextSelectionRule.denyingKinds(
            "context.policy.deny-model-output",
            100,
            "A model's own earlier output is not project state. Feeding it back would let the model"
                + " become the author of its own context, so a mistake in one answer would harden"
                + " into a fact in the next.",
            Set.of(ContextKind.PROMPT_RESULT)),
        ContextSelectionRule.denyingKinds(
            "context.policy.deny-unclassified-notes",
            110,
            "A note is what the project could not classify. It stays readable in memory, but it"
                + " does not displace an item that bears directly on the current state or on the"
                + " task at hand.",
            Set.of(ContextKind.NOTE)),
        ContextSelectionRule.allowing(
            "context.policy.project-identity-and-vision",
            200,
            "A pack opens by naming the project it describes and what that project is for. Without"
                + " those two, every other item is about nothing in particular.",
            Set.of(ContextSourceType.PROJECT),
            Set.of(ContextKind.PROJECT_IDENTITY, ContextKind.VISION)),
        ContextSelectionRule.allowingSources(
            "context.policy.current-task",
            210,
            "The task in front of the user is what this context is being assembled for. Its"
                + " objective and its own state are the items nothing else can substitute for.",
            Set.of(ContextSourceType.CURRENT_TASK)),
        ContextSelectionRule.allowingSources(
            "context.policy.acceptance-criteria",
            220,
            "What finished means for the current task bounds the work as directly as the task"
                + " statement does, and is the part a reader is most likely to be missing.",
            Set.of(ContextSourceType.ACCEPTANCE_CRITERIA)),
        ContextSelectionRule.allowingSources(
            "context.policy.current-phase",
            230,
            "The phase in progress says which part of the plan the current task belongs to, which"
                + " is the only part of the roadmap that changes what to do next.",
            Set.of(ContextSourceType.CURRENT_PHASE)),
        ContextSelectionRule.allowingSources(
            "context.policy.computed-current-state",
            240,
            "Where the project actually stands, as the system computes it from its own records."
                + " This is the state the work has to start from, and being computed rather than"
                + " remembered it cannot be stale in the way a written claim can.",
            Set.of(ContextSourceType.CURRENT_STATE)),
        ContextSelectionRule.allowingSources(
            "context.policy.active-errors",
            250,
            "An open problem changes what the next step should be. Omitting one produces work that"
                + " is correct about a project that no longer exists.",
            Set.of(ContextSourceType.ACTIVE_ERRORS)),
        ContextSelectionRule.allowingSources(
            "context.policy.latest-observed-result",
            260,
            "The most recent recorded evidence of work actually run, and the analysis of it. This"
                + " is the only item that says whether the last attempt worked.",
            Set.of(ContextSourceType.LATEST_EVIDENCE, ContextSourceType.LATEST_OUTPUT_ANALYSIS)),
        ContextSelectionRule.allowingSources(
            "context.policy.security-posture",
            270,
            "The security posture, as a summary of what is outstanding — locations and severities,"
                + " never an offending value. Admitting it informs the work; it resolves nothing."
                + " Whether a finding is closed remains the Security Gate's decision.",
            Set.of(ContextSourceType.SECURITY_SUMMARY)),
        ContextSelectionRule.allowing(
            "context.policy.standing-memory",
            280,
            "Official memory that still binds the project: what it is for, what it must do, how it"
                + " is built, what it is built with, and what it has decided or bound itself to."
                + " These outlive the current task, which is exactly why they are not derivable"
                + " from it.",
            Set.of(ContextSourceType.BRAIN_ENTRY),
            Set.of(
                ContextKind.VISION,
                ContextKind.REQUIREMENT,
                ContextKind.ARCHITECTURE,
                ContextKind.TECHNOLOGY,
                ContextKind.DECISION,
                ContextKind.RULE,
                ContextKind.CONSTRAINT)));
  }
}
