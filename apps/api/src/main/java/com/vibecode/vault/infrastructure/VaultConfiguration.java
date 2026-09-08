package com.vibecode.vault.infrastructure;

import com.vibecode.vault.domain.KeyEncryptionProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Builds the key provider, and refuses to start when it cannot be built safely.
 *
 * <p>Both guards here fail startup rather than degrade. A vault that quietly generates a key would
 * make every previously stored secret unreadable; a production deployment quietly using a key from
 * an environment variable is the accident worth an outage to prevent.
 */
@Configuration
@EnableConfigurationProperties(VaultProperties.class)
public class VaultConfiguration {

  @Bean
  public KeyEncryptionProvider keyEncryptionProvider(
      VaultProperties properties, Environment environment) {

    if (!properties.isEnabled()) {
      throw new LocalKeyEncryptionProvider.VaultStartupException(
          "The vault is disabled, but provider credentials cannot be stored without it. Enable it "
              + "and supply a master key, or remove the credential endpoints.");
    }

    if (properties.getKeyProvider() == VaultProperties.KeyProvider.LOCAL) {
      guardAgainstLocalKeyInProduction(properties, environment);
      return new LocalKeyEncryptionProvider(
          properties.getLocal().getMasterKey(), properties.getLocal().getKeyVersion());
    }

    throw new LocalKeyEncryptionProvider.VaultStartupException(
        "Unsupported vault key provider: " + properties.getKeyProvider());
  }

  /**
   * Stops a production deployment from running on a local master key.
   *
   * <p>The opt-in exists because there are legitimate single-host deployments, but it has to be
   * typed out deliberately — nobody reaches it by copying a config file without noticing.
   */
  private void guardAgainstLocalKeyInProduction(
      VaultProperties properties, Environment environment) {
    boolean production =
        environment.matchesProfiles("prod")
            || environment.matchesProfiles("production");
    if (production && !properties.isAllowLocalKeyProviderOutsideDevelopment()) {
      throw new LocalKeyEncryptionProvider.VaultStartupException(
          "The local key provider keeps the master key in an environment variable and is intended "
              + "for development. To run it in production anyway, set "
              + "vibecode.vault.allow-local-key-provider-outside-development=true and accept that "
              + "the key is only as protected as the environment holding it.");
    }
  }
}
