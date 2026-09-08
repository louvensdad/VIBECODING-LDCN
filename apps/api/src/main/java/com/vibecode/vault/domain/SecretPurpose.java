package com.vibecode.vault.domain;

/**
 * What a stored secret is for.
 *
 * <p>Part of the authenticated data, so a ciphertext encrypted for one purpose cannot be presented
 * as another. Only the first is used today; the rest are named so the enum does not have to change
 * shape when they arrive.
 */
public enum SecretPurpose {
  PROVIDER_API_KEY,
  OAUTH_ACCESS_TOKEN,
  OAUTH_REFRESH_TOKEN,
  DATABASE_CREDENTIAL,
  CLOUD_CREDENTIAL,
  WEBHOOK_SECRET
}
