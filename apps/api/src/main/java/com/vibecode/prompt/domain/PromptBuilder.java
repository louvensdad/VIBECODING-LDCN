package com.vibecode.prompt.domain;

/**
 * Builds prompts from official context. Contract only in this phase — no implementation and no
 * model call.
 */
public interface PromptBuilder {

  GeneratedPrompt build(PromptRequest request, PromptContext context);
}
