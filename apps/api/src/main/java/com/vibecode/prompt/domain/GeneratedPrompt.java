package com.vibecode.prompt.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A prompt ready to be copied into any external model.
 *
 * <p>{@code contextSources} names what went into it, so the user can see exactly what they are
 * about to send somewhere else before they send it.
 *
 * <p>Prompt safety metadata guarantees that prompts with detected secrets are blocked from
 * copying to external LLMs.
 */
public record GeneratedPrompt(
    PromptType type,
    UUID taskId,
    String taskTitle,
    String content,
    List<String> contextSources,
    PromptSecurityStatus securityStatus,
    boolean copyAllowed,
    List<String> securityFindings,
    Instant generatedAt) {

  public GeneratedPrompt {
    contextSources = contextSources == null ? List.of() : List.copyOf(contextSources);
    securityFindings = securityFindings == null ? List.of() : List.copyOf(securityFindings);
    securityStatus = securityStatus == null ? PromptSecurityStatus.SAFE : securityStatus;
  }

  public GeneratedPrompt(
      PromptType type,
      UUID taskId,
      String taskTitle,
      String content,
      List<String> contextSources,
      Instant generatedAt) {
    this(
        type,
        taskId,
        taskTitle,
        content,
        contextSources,
        PromptSecurityStatus.SAFE,
        true,
        List.of(),
        generatedAt);
  }
}
