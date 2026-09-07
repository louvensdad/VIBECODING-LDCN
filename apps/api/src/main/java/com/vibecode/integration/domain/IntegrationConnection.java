package com.vibecode.integration.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A connection to an external system.
 *
 * <p>There is no credential field, and there will not be one: secrets live in the environment or a
 * secret store and are referenced by {@code credentialRef}, never persisted or logged here.
 */
public record IntegrationConnection(
    UUID id,
    UUID projectId,
    IntegrationId integration,
    String label,
    String credentialRef,
    ConnectionStatus status,
    Instant connectedAt) {

  public enum ConnectionStatus {
    NOT_CONNECTED,
    CONNECTED,
    EXPIRED,
    ERROR
  }
}
