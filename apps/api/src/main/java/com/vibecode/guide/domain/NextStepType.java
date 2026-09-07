package com.vibecode.guide.domain;

/** What kind of action the platform is recommending. */
public enum NextStepType {
  START_TASK,
  CONTINUE_TASK,
  /** A failure is on the table. Nothing new starts until it is fixed. */
  FIX_ERROR,
  /** Work was reported done but the evidence does not close it. */
  VALIDATE_RESULT,
  /** Something outside the code is in the way. */
  RESOLVE_BLOCKER,
  /** A critical-risk task should be thought through before code is written. */
  REVIEW_SECURITY,
  /** The platform cannot decide alone; the user has to supply structure or a decision. */
  WAIT_FOR_USER,
  PROJECT_COMPLETE
}
