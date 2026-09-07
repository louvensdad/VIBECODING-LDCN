package com.vibecode.prompt.domain;

import java.util.UUID;

/** Asks for a prompt of a given type for a given task. */
public record PromptRequest(UUID projectId, UUID taskId, PromptType type, String userNote) {}
