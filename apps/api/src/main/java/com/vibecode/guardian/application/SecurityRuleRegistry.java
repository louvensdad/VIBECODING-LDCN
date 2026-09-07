package com.vibecode.guardian.application;

import com.vibecode.guardian.domain.SecurityInspectionContext;
import com.vibecode.guardian.domain.SecurityRule;
import java.util.List;
import org.springframework.stereotype.Component;

/** Holds registered deterministic security rules. */
@Component
public class SecurityRuleRegistry {

  private final List<SecurityRule> rules;

  public SecurityRuleRegistry(List<SecurityRule> rules) {
    this.rules = List.copyOf(rules);
  }

  public List<SecurityRule> rulesFor(SecurityInspectionContext context) {
    return rules.stream().filter(rule -> rule.supports(context)).toList();
  }

  public List<SecurityRule> allRules() {
    return rules;
  }
}

