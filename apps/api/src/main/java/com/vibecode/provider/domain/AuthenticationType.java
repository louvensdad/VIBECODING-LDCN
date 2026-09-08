package com.vibecode.provider.domain;

/** How a connection authenticates. Only the first is implemented. */
public enum AuthenticationType {
  API_KEY,
  OAUTH,
  SERVICE_ACCOUNT,
  CUSTOM_TOKEN;

  public boolean isImplemented() {
    return this == API_KEY;
  }
}
