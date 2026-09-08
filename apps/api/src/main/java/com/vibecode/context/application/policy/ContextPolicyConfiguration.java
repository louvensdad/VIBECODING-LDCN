package com.vibecode.context.application.policy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the policy in force as a single bean.
 *
 * <p>One bean rather than a component-scanned rule per class, on purpose. The evaluation order is a
 * property of the policy as a whole, and a scanned set of rules would put that order at the mercy
 * of what happened to be on the classpath — a rule accidentally left in a test source set, or one
 * quietly excluded by a profile, would change what a pack contains with nothing to show for it.
 * {@link DefaultContextPolicyRules} is the list, it is read in one place, and it is diffable.
 */
@Configuration
public class ContextPolicyConfiguration {

  /** The policy at {@link com.vibecode.context.domain.ContextPolicyVersion#CURRENT}. */
  @Bean
  public ContextPolicy contextPolicy() {
    return ContextPolicy.current();
  }
}
