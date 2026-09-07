package com.vibecode.prompt.web;

import com.vibecode.prompt.application.DeterministicPromptBuilder;
import com.vibecode.prompt.domain.GeneratedPrompt;
import com.vibecode.prompt.domain.PromptRequest;
import com.vibecode.prompt.domain.PromptType;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generates the prompt the user pastes into whichever model they are using.
 *
 * <p>No provider is called from here, and none will be: this endpoint returns text.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/prompts")
public class PromptController {

  private final DeterministicPromptBuilder builder;

  public PromptController(DeterministicPromptBuilder builder) {
    this.builder = builder;
  }

  @PostMapping("/generate")
  public GeneratedPromptResponse generate(
      @PathVariable UUID projectId, @Valid @RequestBody GeneratePromptRequest request) {
    return GeneratedPromptResponse.from(
        builder.build(new PromptRequest(projectId, request.taskId(), request.type())));
  }

  /** Both fields are optional: omitted, the recommended task and prompt type are used. */
  public record GeneratePromptRequest(UUID taskId, PromptType type) {}

  public record GeneratedPromptResponse(
      PromptType type,
      UUID taskId,
      String taskTitle,
      String content,
      List<String> contextSources,
      com.vibecode.prompt.domain.PromptSecurityStatus securityStatus,
      boolean copyAllowed,
      List<String> securityFindings,
      Instant generatedAt) {

    static GeneratedPromptResponse from(GeneratedPrompt prompt) {
      return new GeneratedPromptResponse(
          prompt.type(),
          prompt.taskId(),
          prompt.taskTitle(),
          prompt.content(),
          prompt.contextSources(),
          prompt.securityStatus(),
          prompt.copyAllowed(),
          prompt.securityFindings(),
          prompt.generatedAt());
    }
  }
}
