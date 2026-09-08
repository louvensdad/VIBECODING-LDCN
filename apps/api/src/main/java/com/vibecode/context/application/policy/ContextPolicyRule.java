package com.vibecode.context.application.policy;

import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextItem;
import java.util.Optional;

/**
 * One rule of the context policy: a named, individually readable answer about a class of items.
 *
 * <p><b>Small on purpose.</b> The alternative — one class holding a long chain of conditions — is
 * untestable in the only way that matters: you cannot ask it "which rule admitted this item", only
 * "did it come out". A rule here has an id that survives into the stored pack, so a person looking
 * at an item months later can find the rule, read it, and disagree with it.
 *
 * <p>A rule returns empty when it has nothing to say about an item. That is not an allow and must
 * never be read as one: an item no rule speaks about is denied by {@link ContextPolicy}, under a
 * reserved rule id, because the absence of a reason to include something is not a reason to include
 * it.
 */
public interface ContextPolicyRule {

  /**
   * The stable id of this rule, unique within a policy and persisted with every item it admits.
   *
   * <p>Stable means it outlives refactoring: renaming the class must not rename this. A stored pack
   * points at it, and a handle that changes is a handle that resolves to nothing.
   */
  String ruleId();

  /**
   * Where this rule sits in the evaluation order. Lower is asked first; the first rule to speak
   * decides.
   *
   * <p>An explicit number rather than declaration order or an ordinal, for the same reason the
   * ordering ranks in the domain are explicit: an order that moves when an unrelated edit reorders
   * a list is an order that silently changes which rule admitted an item, and therefore what a
   * stored pack says about itself.
   */
  int evaluationRank();

  /**
   * This rule's decision about one candidate, or empty if the rule does not apply to it.
   *
   * <p>Implementations are pure: same item, same answer, every time. A rule that consulted a clock,
   * a counter or the outcome of a previous call would make a pack irreproducible and its stored
   * explanation a lie about what happens today.
   */
  Optional<ContextAdmission> evaluate(ContextItem item);
}
