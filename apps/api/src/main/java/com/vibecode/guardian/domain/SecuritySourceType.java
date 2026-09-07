package com.vibecode.guardian.domain;

/** The provenance of inspected content. */
public enum SecuritySourceType {
  TASK_EVIDENCE,
  PROMPT,
  BRAIN_ENTRY,
  USER_INPUT,
  OUTPUT_ANALYSIS,
  TERMINAL_OUTPUT,
  GENERIC_TEXT
}
