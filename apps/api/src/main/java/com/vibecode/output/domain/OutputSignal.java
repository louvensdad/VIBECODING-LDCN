package com.vibecode.output.domain;

/**
 * A single piece of evidence found in an output.
 *
 * <p>The severity, not the signal's name, drives the verdict — which is how a bare "concluído"
 * stays a {@link Severity#SUCCESS_CLAIM} and never outranks a real failure.
 */
public enum OutputSignal {
  BUILD_SUCCESS(Severity.STRONG_SUCCESS),
  TESTS_PASSED(Severity.STRONG_SUCCESS),
  MIGRATION_APPLIED(Severity.STRONG_SUCCESS),
  HTTP_OK(Severity.STRONG_SUCCESS),

  BUILD_FAILURE(Severity.HARD_FAILURE),
  COMPILATION_ERROR(Severity.HARD_FAILURE),
  TESTS_FAILED(Severity.HARD_FAILURE),
  EXCEPTION(Severity.HARD_FAILURE),
  HTTP_SERVER_ERROR(Severity.HARD_FAILURE),

  ERROR(Severity.SOFT_FAILURE),
  FAILED(Severity.SOFT_FAILURE),

  PERMISSION_DENIED(Severity.BLOCKING),
  QUOTA_EXCEEDED(Severity.BLOCKING),
  AUTHENTICATION_REQUIRED(Severity.BLOCKING),

  /** Someone said it worked. Not evidence that it worked. */
  CLAIMED_COMPLETION(Severity.SUCCESS_CLAIM);

  public enum Severity {
    BLOCKING,
    HARD_FAILURE,
    SOFT_FAILURE,
    STRONG_SUCCESS,
    SUCCESS_CLAIM
  }

  private final Severity severity;

  OutputSignal(Severity severity) {
    this.severity = severity;
  }

  public Severity severity() {
    return severity;
  }

  public boolean is(Severity candidate) {
    return severity == candidate;
  }
}
