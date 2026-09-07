package com.vibecode.guardian.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One risk a guardian observed.
 *
 * <p>{@code recommendation} is required: a warning the user cannot act on is noise. Findings never
 * quote the offending value — a secret-detection finding names the location, not the secret.
 */
public record GuardianFinding(
    UUID id,
    UUID projectId,
    GuardianId guardian,
    GuardianSeverity severity,
    String title,
    String explanation,
    String recommendation,
    String location,
    Instant detectedAt) {}
