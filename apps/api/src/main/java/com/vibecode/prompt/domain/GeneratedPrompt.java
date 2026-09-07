package com.vibecode.prompt.domain;

import java.time.Instant;
import java.util.List;

/**
 * A prompt ready to be copied into any external model.
 *
 * <p>{@code contextSources} lists what went into it, so the user can see exactly what they are
 * about to send somewhere else before they send it.
 */
public record GeneratedPrompt(
    PromptType type, String content, List<String> contextSources, Instant generatedAt) {

  public GeneratedPrompt {
    contextSources = List.copyOf(contextSources);
  }
}
