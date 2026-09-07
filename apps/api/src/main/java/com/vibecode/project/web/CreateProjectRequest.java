package com.vibecode.project.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload for creating a tracked project. */
public record CreateProjectRequest(
    @NotBlank @Size(max = 120) String name,
    @Size(max = 1000) String description,
    @NotBlank @Size(max = 10_000) String originalIdea) {}
