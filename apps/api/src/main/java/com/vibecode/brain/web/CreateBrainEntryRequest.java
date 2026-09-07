package com.vibecode.brain.web;

import com.vibecode.brain.domain.BrainEntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload for writing one entry into official memory.
 *
 * <p>{@code source} is required: memory without provenance cannot be judged later.
 */
public record CreateBrainEntryRequest(
    @NotNull BrainEntryType type,
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 50_000) String content,
    @NotBlank @Size(max = 80) String source) {}
