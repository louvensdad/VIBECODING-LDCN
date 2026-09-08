package com.vibecode.vault.domain;

import java.util.UUID;

/**
 * What the rest of the platform holds instead of a secret.
 *
 * <p>The principle in one type: secrets are references, never context. A reference can be stored on
 * an entity, returned in a DTO and written to an audit row without any of them touching plaintext.
 */
public record SecretReference(UUID secretId, SecretPurpose purpose) {

  public SecretReference {
    if (secretId == null) {
      throw new IllegalArgumentException("A secret reference needs an id");
    }
  }
}
