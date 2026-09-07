package com.vibecode.output.domain;

/** What kind of fact an evidence record captures. */
public enum EvidenceType {
  LLM_RESPONSE,
  TERMINAL_OUTPUT,
  BUILD_RESULT,
  TEST_RESULT,
  ERROR_LOG,
  HTTP_RESPONSE,
  DATABASE_RESULT,
  /** The user vouching for something a machine cannot check. */
  USER_CONFIRMATION,
  GENERIC_OUTPUT
}
