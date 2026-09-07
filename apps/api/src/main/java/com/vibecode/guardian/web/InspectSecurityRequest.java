package com.vibecode.guardian.web;

import com.vibecode.guardian.domain.SecuritySourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InspectSecurityRequest(
    @NotNull SecuritySourceType sourceType,
    String sourceId,
    @NotBlank String content) {}

