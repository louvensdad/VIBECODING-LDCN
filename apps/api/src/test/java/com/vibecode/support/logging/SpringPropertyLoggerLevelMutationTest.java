package com.vibecode.support.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Which route actually mutates the JVM-wide logger context, established rather than assumed.
 *
 * <p>It is the property route. Spring Boot's logging listener applies {@code logging.level.*} to
 * Logback while it builds the application context, and the level it sets is an explicit level on a
 * real logger — indistinguishable, afterwards, from one a test set by hand.
 *
 * <p>What this class proves is exactly that mutation, and no more. It does not observe the level
 * outliving the class, because the class carries {@link LoggerLevelIsolation} and so it does not:
 * the name says mutation rather than leak for that reason. That the mutation would persist is
 * Spring's doing rather than something asserted here — the listener only ever adds, and a
 * {@code @SpringBootTest} context is cached for the rest of the run rather than closed, so nothing
 * fires that could take a level back off. The half that is asserted, that the probe is back to
 * inheriting once this class is over, belongs to
 * {@link LoggingScenarios.ExpectsTheDefaultConfiguration}, which runs in the same JVM in whichever
 * order Surefire chooses.
 *
 * <p>The context is a bare {@code @Configuration} with no auto-configuration and no web
 * environment. A full application context would prove the same thing and cost seconds; what is
 * under test is Spring Boot's logging listener, which runs for any context at all. It is the same
 * configuration class the observing half uses, but the property below gives it a different cache
 * key, so this class gets a context of its own and really does trigger a fresh refresh.
 */
@SpringBootTest(
    classes = LoggingScenarios.ApplicationConfigurationOnly.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = "logging.level." + LoggingScenarios.PROBE + "=TRACE")
@ExtendWith(LoggerLevelIsolation.class)
class SpringPropertyLoggerLevelMutationTest {

  @Test
  @DisplayName("A logging.level property really does set an explicit level on the JVM's logger")
  void thePropertyRouteMutatesTheLoggerContext() {
    assertThat(LoggerLevels.explicitLevelOf(LoggingScenarios.PROBE))
        .as("if this is null the property route stopped leaking and this proof is stale")
        .isEqualTo(Level.TRACE);
  }
}
