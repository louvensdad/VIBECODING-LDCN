package com.vibecode.guardian.domain;

import java.util.List;

/** Contract for a deterministic security rule evaluated by the Guardian. */
public interface SecurityRule {

  RuleId id();

  boolean supports(SecurityInspectionContext context);

  List<SecurityFindingCandidate> inspect(SecurityInspectionContext context);
}
