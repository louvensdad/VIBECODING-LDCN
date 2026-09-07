package com.vibecode.guardian.domain;

import java.util.List;
import java.util.Objects;

/** The result of passing project findings through the safety gate. */
public record SecurityGateResult(
    SecurityGateStatus status,
    boolean canProceed,
    List<String> blockingReasons,
    List<String> warnings) {

  public SecurityGateResult {
    Objects.requireNonNull(status, "status cannot be null");
    blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  public static SecurityGateResult evaluate(List<SecurityFinding> findings) {
    long criticalOpen =
        findings.stream()
            .filter(f -> f.getStatus().isOpen() && f.getSeverity() == SecuritySeverity.CRITICAL)
            .count();
    long highOpen =
        findings.stream()
            .filter(f -> f.getStatus().isOpen() && f.getSeverity() == SecuritySeverity.HIGH)
            .count();
    long mediumOpen =
        findings.stream()
            .filter(f -> f.getStatus().isOpen() && f.getSeverity() == SecuritySeverity.MEDIUM)
            .count();
    long lowOpen =
        findings.stream()
            .filter(f -> f.getStatus().isOpen() && f.getSeverity() == SecuritySeverity.LOW)
            .count();

    if (criticalOpen > 0) {
      return new SecurityGateResult(
          SecurityGateStatus.BLOCKED,
          false,
          List.of(criticalOpen + " problema(s) crítico(s) de segurança aberto(s)"),
          List.of());
    }
    if (highOpen > 0) {
      return new SecurityGateResult(
          SecurityGateStatus.REQUIRES_APPROVAL,
          false,
          List.of(highOpen + " problema(s) de alta severidade pendente(s) de aprovação ou resolução"),
          List.of());
    }
    if (mediumOpen > 0 || lowOpen > 0) {
      return new SecurityGateResult(
          SecurityGateStatus.WARNING,
          true,
          List.of(),
          List.of((mediumOpen + lowOpen) + " aviso(s) de segurança em aberto"));
    }
    return new SecurityGateResult(SecurityGateStatus.PASS, true, List.of(), List.of());
  }
}
