package com.vibecode.model.domain;

/** What a model can do, used to pick a target and to warn before a handoff loses an ability. */
public enum ModelCapability {
  TEXT,
  LONG_CONTEXT,
  VISION,
  TOOL_USE,
  CODE_EXECUTION,
  STRUCTURED_OUTPUT
}
