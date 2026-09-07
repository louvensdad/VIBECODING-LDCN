package com.vibecode.prompt.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A prompt ready to be copied into any external model.
 *
 * <p>{@code contextSources} names what went into it, so the user can see exactly what they are
 * about to send somewhere else before they send it.
 */
public record GeneratedPrompt(
    PromptType type,
    UUID taskId,
    String taskTitle,
    String content,
    List<String> contextSources,
    Instant generatedAt) {

  public GeneratedPrompt {
    contextSources = List.copyOf(contextSources);
  }
}
