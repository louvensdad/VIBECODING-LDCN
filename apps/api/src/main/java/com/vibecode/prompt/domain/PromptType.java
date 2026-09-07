package com.vibecode.prompt.domain;

/** Why a prompt is being generated. */
public enum PromptType {
  START_TASK,
  CONTINUE_TASK,
  FIX_ERROR,
  VALIDATE_RESULT,
  /** Carries context to a different model so the project survives the switch. */
  MODEL_HANDOFF,
  /** Asks the model to produce proof, used when an output only claimed success. */
  ASK_FOR_EVIDENCE
}
