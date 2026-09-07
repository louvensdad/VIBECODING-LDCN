package com.vibecode.output.domain;

/** What the user pasted in. Recorded for context; the analyzer reads the text either way. */
public enum OutputKind {
  LLM_RESPONSE,
  TERMINAL,
  STACK_TRACE,
  BUILD,
  TEST,
  LOG,
  HTTP_RESPONSE,
  SQL,
  DEPLOY,
  GENERIC_TEXT
}
