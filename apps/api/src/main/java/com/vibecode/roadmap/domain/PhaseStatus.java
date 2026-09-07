package com.vibecode.roadmap.domain;

/**
 * Lifecycle of a roadmap phase.
 *
 * <p>Phase status is <em>derived</em> from the tasks inside it, never set by hand — see {@code
 * PhaseStatusCalculator}. That keeps the phase from disagreeing with its own tasks.
 */
public enum PhaseStatus {
  /** No task is workable yet. */
  PLANNED,
  /** At least one task has its dependencies satisfied. */
  READY,
  IN_PROGRESS,
  /** Some task hit an external obstacle. */
  BLOCKED,
  COMPLETED,
  SKIPPED
}
