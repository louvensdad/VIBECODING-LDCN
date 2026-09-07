package com.vibecode.output.domain;

/** The verdict the analyzer reaches about one piece of output. */
public enum OutputAnalysisStatus {
  /** Technical evidence that the step worked. */
  SUCCESS,
  /** Evidence of success, but errors were also reported. A human must look. */
  PARTIAL,
  /** Technical evidence that the step failed. */
  FAILURE,
  /** Something outside the code is in the way: permissions, quota, credentials. */
  BLOCKED,
  /** No technical evidence either way — a claim of success is not evidence. */
  NEEDS_VALIDATION,
  /** Nothing analyzable in the input. */
  UNKNOWN
}
