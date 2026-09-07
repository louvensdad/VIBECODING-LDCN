package com.vibecode.usage.domain;

import com.vibecode.model.domain.ProviderId;
import java.time.Instant;
import java.util.UUID;

/**
 * An account the user has with a model provider.
 *
 * <p>No API key is stored on this record. {@code credentialRef} points at a secret held outside the
 * database.
 */
public record ProviderAccount(
    UUID id,
    ProviderId provider,
    String label,
    String credentialRef,
    boolean active,
    Instant connectedAt) {}
