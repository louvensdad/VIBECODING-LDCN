package com.vibecode.vault.domain;

/** Lifecycle of a stored secret as a whole, independent of its versions. */
public enum SecretStatus {
  ACTIVE,
  /** The user removed the credential; no version can be decrypted any more. */
  DESTROYED
}
