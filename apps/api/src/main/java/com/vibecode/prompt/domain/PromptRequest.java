package com.vibecode.prompt.domain;

import java.util.UUID;

/**
 * Asks for a prompt. Both fields may be omitted: with no type the Next Step Engine's suggestion is
 * used, and with no task the recommended task is.
 */
public record PromptRequest(UUID projectId, UUID taskId, PromptType type) {}
