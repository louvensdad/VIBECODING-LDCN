package com.vibecode.task.domain;

/**
 * Lifecycle of a task.
 *
 * <p>The distinction that matters most is {@link #BLOCKED} versus a failing task. A failed build is
 * ordinary work still in progress — the user fixes it. BLOCKED means something outside the code is
 * in the way (a permission, a credential, an exhausted quota) and no amount of coding clears it.
 * Collapsing the two would strand a task nobody is actually blocked on.
 */
public enum TaskStatus {
  /** Has unfinished mandatory dependencies. */
  PLANNED,
  /** Dependencies satisfied; can be started. */
  READY,
  IN_PROGRESS,
  /** Stopped by an external obstacle, not by a defect. */
  BLOCKED,
  /** Work was reported done, but the evidence does not close it yet. */
  NEEDS_VALIDATION,
  COMPLETED,
  SKIPPED;

  public boolean isFinished() {
    return this == COMPLETED || this == SKIPPED;
  }

  /** Statuses the user is actively holding; the recalculator must not overwrite them. */
  public boolean isUserHeld() {
    return this == IN_PROGRESS || this == BLOCKED || this == NEEDS_VALIDATION;
  }
}
