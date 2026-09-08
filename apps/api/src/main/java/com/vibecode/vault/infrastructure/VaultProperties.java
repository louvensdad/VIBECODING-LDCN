package com.vibecode.vault.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Vault configuration.
 *
 * <p>The master key is read from the environment and never has a default. A vault that invents a
 * key when one is missing would silently make every previously stored secret undecryptable, so the
 * application refuses to start instead.
 */
@ConfigurationProperties(prefix = "vibecode.vault")
public class VaultProperties {

  public enum KeyProvider {
    /** Master key from the environment. Development only. */
    LOCAL
  }

  private boolean enabled = true;
  private KeyProvider keyProvider = KeyProvider.LOCAL;

  /**
   * Allows the local key provider outside development.
   *
   * <p>Off by default and expected to stay off: a production deployment holding customer
   * credentials under a key sitting in an environment variable is the accident this flag exists to
   * prevent.
   */
  private boolean allowLocalKeyProviderOutsideDevelopment = false;

  private Local local = new Local();

  public static class Local {

    /** Base64 of exactly 32 random bytes. Never written to a file in this repository. */
    private String masterKey;

    private String keyVersion = "local-v1";

    public String getMasterKey() {
      return masterKey;
    }

    public void setMasterKey(String masterKey) {
      this.masterKey = masterKey;
    }

    public String getKeyVersion() {
      return keyVersion;
    }

    public void setKeyVersion(String keyVersion) {
      this.keyVersion = keyVersion;
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public KeyProvider getKeyProvider() {
    return keyProvider;
  }

  public void setKeyProvider(KeyProvider keyProvider) {
    this.keyProvider = keyProvider;
  }

  public boolean isAllowLocalKeyProviderOutsideDevelopment() {
    return allowLocalKeyProviderOutsideDevelopment;
  }

  public void setAllowLocalKeyProviderOutsideDevelopment(boolean allow) {
    this.allowLocalKeyProviderOutsideDevelopment = allow;
  }

  public Local getLocal() {
    return local;
  }

  public void setLocal(Local local) {
    this.local = local;
  }
}
