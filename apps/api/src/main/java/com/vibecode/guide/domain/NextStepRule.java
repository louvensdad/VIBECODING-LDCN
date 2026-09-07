package com.vibecode.guide.domain;

import java.util.Optional;

/**
 * One deterministic rule.
 *
 * <p>Rules are small and independent so each can be tested on its own, and so the engine stays an
 * ordered list instead of a nested chain of conditionals.
 */
public interface NextStepRule {

  /** The recommendation this rule makes, or empty when it does not apply. */
  Optional<NextStepRecommendation> evaluate(NextStepContext context);

  /** Short name used in tests and in the reason trail. */
  String name();
}
