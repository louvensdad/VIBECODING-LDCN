package com.vibecode.model.domain;

import java.util.UUID;

/**
 * A vendor-neutral request.
 *
 * <p>No provider SDK type may appear in this record, or the rest of the application would start
 * depending on one vendor.
 */
public record ModelRequest(
    UUID projectId, String modelId, String systemPrompt, String prompt, Integer maxOutputTokens) {}
