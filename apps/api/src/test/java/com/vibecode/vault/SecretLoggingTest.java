package com.vibecode.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vibecode.identity.domain.User;
import com.vibecode.provider.application.ProviderAccountService;
import com.vibecode.provider.domain.AuthenticationType;
import com.vibecode.provider.domain.ProviderAccount;
import com.vibecode.provider.domain.ProviderId;
import com.vibecode.support.TestIdentity;
import com.vibecode.support.logging.LoggerLevelIsolation;
import com.vibecode.vault.domain.SecretMaterial;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Logs are the leak nobody notices.
 *
 * <p>A credential in a response is caught in review. A credential in a log line is shipped to a
 * log aggregator, indexed, retained for a year and read by people who never touched this code.
 * This captures everything logged at DEBUG and above during a real credential ingestion and checks
 * it.
 *
 * <p>Raising the root logger is a change to a JVM-wide object, so it is put back by
 * {@link LoggerLevelIsolation} rather than by an {@code @AfterEach} here. One extension is one
 * place to get it right; a line of cleanup in every class that touches a level is a line that will
 * be missing from the next one.
 */
@ExtendWith(LoggerLevelIsolation.class)
@SpringBootTest
class SecretLoggingTest {

  // Shared with ProviderAccountApiTest and VaultStorageTest on purpose: that class forbids this
  // credential's last four characters in every audit row, which is the only thing standing between
  // a masked-suffix leak of this fixture and nobody noticing. Non-hex for the same reason it is
  // there — the corpus these fragments are hunted in is made of identifiers.
  private static final String CREDENTIAL = "vc_anthropic_test_secret_92zqxw";

  @Autowired ProviderAccountService accounts;
  @Autowired TestIdentity testIdentity;

  private Logger root;
  private ListAppender<ILoggingEvent> captured;
  private User owner;

  @BeforeEach
  void startCapturing() {
    owner = testIdentity.createAndAuthenticate("logging-owner");

    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    // Deliberately noisy: a DEBUG line is still a line in a file somewhere. The level is not
    // remembered here; the extension on the class took a snapshot of every logger before this ran.
    root.setLevel(Level.DEBUG);

    captured = new ListAppender<>();
    captured.start();
    root.addAppender(captured);
  }

  @AfterEach
  void stopCapturing() {
    root.detachAppender(captured);
    captured.stop();
    testIdentity.clear();
  }

  private SecretMaterial material() {
    return SecretMaterial.of(CREDENTIAL.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("Storing, rotating and removing a credential logs nothing about its value")
  void lifecycleLogsNothingSensitive() {
    ProviderAccount account =
        accounts.create(ProviderId.ANTHROPIC, "My Claude key", AuthenticationType.API_KEY);

    accounts.storeCredential(account.getId(), material());
    accounts.rotateCredential(
        account.getId(),
        SecretMaterial.of("vc_anthropic_test_secret_92zqxy".getBytes(StandardCharsets.UTF_8)));
    accounts.removeCredential(account.getId());

    assertNothingLeaked();
  }

  @Test
  @DisplayName("A failure logs the failure, not the credential")
  void failuresLogNothingSensitive() {
    ProviderAccount account =
        accounts.create(ProviderId.ANTHROPIC, "My Claude key", AuthenticationType.API_KEY);

    // Rotating a connection that has no credential yet: a real error path, with a real exception
    // travelling through the logging framework.
    assertThatThrownBy(() -> accounts.rotateCredential(account.getId(), material()))
        .isInstanceOf(RuntimeException.class);

    assertNothingLeaked();
  }

  @Test
  @DisplayName("Nothing holding a credential prints one when it is turned into a string")
  void toStringNeverRevealsMaterial() {
    SecretMaterial material = material();

    // The most common way a secret escapes is not a deliberate log call. It is an object with a
    // default toString ending up inside a log message, an exception, or a debugger transcript.
    assertThat(material.toString()).doesNotContain(CREDENTIAL);

    ProviderAccount account =
        accounts.create(ProviderId.ANTHROPIC, "My Claude key", AuthenticationType.API_KEY);
    accounts.storeCredential(account.getId(), material);

    assertThat(account.toString()).doesNotContain(CREDENTIAL);
  }

  private void assertNothingLeaked() {
    List<ILoggingEvent> events = List.copyOf(captured.list);
    assertThat(events).isNotEmpty();

    for (ILoggingEvent event : events) {
      String line = event.getFormattedMessage() + " " + String.valueOf(event.getThrowableProxy());
      assertThat(line).doesNotContain(CREDENTIAL);
      // Not a fragment of it either.
      assertThat(line).doesNotContain(CREDENTIAL.substring(0, 12));
      assertThat(line).doesNotContain("92zqxw").doesNotContain("92zqxy");
    }
  }
}
