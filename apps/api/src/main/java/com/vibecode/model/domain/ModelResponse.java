package com.vibecode.model.domain;

/** A vendor-neutral response, including the token counts the usage module bills against. */
public record ModelResponse(
    String content, String modelId, long inputTokens, long outputTokens, String stopReason) {}
