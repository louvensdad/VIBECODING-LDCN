package com.vibecode.support.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The mechanism itself, exercised many times over rather than once.
 *
 * <p>The two ordering classes prove the extension works through the real JUnit lifecycle, but a
 * container lifecycle runs once per class and a proof that happens once is a proof that could have
 * been luck. This drives {@link LoggerLevels} directly, in both orders, repeatedly, and it costs
 * milliseconds because it touches nothing but the {@code LoggerContext}. It waits for nothing, so
 * there is nothing here to sleep on.
 */
@SpringBootTest(
    classes = LoggingScenarios.ApplicationConfigurationOnly.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(LoggerLevelIsolation.class)
class LoggerLevelsRestoreTest {

  private static final int REPETITIONS = 250;

  @Test
  @DisplayName("Raise-then-observe and observe-then-raise leave the same levels, every time")
  void bothOrdersLeaveTheSameLevels() {
    for (int iteration = 0; iteration < REPETITIONS; iteration++) {
      assertOrderIsIrrelevant();
    }
  }

  private void assertOrderIsIrrelevant() {
    Level probeBefore = LoggerLevels.explicitLevelOf(LoggingScenarios.PROBE);
    Level rootBefore = LoggerLevels.explicitLevelOf(org.slf4j.Logger.ROOT_LOGGER_NAME);

    // A -> B
    LoggerLevels beforeA = LoggerLevels.snapshot();
    set(LoggingScenarios.PROBE, Level.TRACE);
    set(org.slf4j.Logger.ROOT_LOGGER_NAME, Level.TRACE);
    beforeA.restore();
    Level probeAfterAThenB = LoggerLevels.explicitLevelOf(LoggingScenarios.PROBE);
    Level rootAfterAThenB = LoggerLevels.explicitLevelOf(org.slf4j.Logger.ROOT_LOGGER_NAME);

    // B -> A: the same two halves, observed first and raised afterwards.
    Level probeBeforeB = LoggerLevels.explicitLevelOf(LoggingScenarios.PROBE);
    Level rootBeforeB = LoggerLevels.explicitLevelOf(org.slf4j.Logger.ROOT_LOGGER_NAME);
    LoggerLevels beforeSecondA = LoggerLevels.snapshot();
    set(LoggingScenarios.PROBE, Level.TRACE);
    set(org.slf4j.Logger.ROOT_LOGGER_NAME, Level.TRACE);
    beforeSecondA.restore();

    assertThat(probeAfterAThenB).isEqualTo(probeBefore).isEqualTo(probeBeforeB);
    assertThat(rootAfterAThenB).isEqualTo(rootBefore).isEqualTo(rootBeforeB);
  }

  @Test
  @DisplayName("A logger that did not exist when the snapshot was taken is left inheriting")
  void loggersInventedAfterTheSnapshotAreCleared() {
    // The case a snapshot-and-put-back that only walked its own map would miss entirely: naming a
    // category in logging.level.* creates the logger, so the categories a test invents are exactly
    // the ones absent from the baseline.
    String invented = "com.vibecode.probe.invented." + UUID.randomUUID();
    LoggerLevels before = LoggerLevels.snapshot();

    set(invented, Level.TRACE);
    assertThat(LoggerLevels.explicitLevelOf(invented)).isEqualTo(Level.TRACE);

    before.restore();

    assertThat(LoggerLevels.explicitLevelOf(invented)).isNull();
  }

  @Test
  @DisplayName("Restoring puts back a configured level rather than clearing it")
  void configuredLevelsSurviveARestore() {
    LoggerLevels before = LoggerLevels.snapshot();
    set(LoggingScenarios.PINNED, Level.TRACE);

    before.restore();

    assertThat(LoggerLevels.explicitLevelOf(LoggingScenarios.PINNED)).isEqualTo(Level.INFO);
  }

  private static void set(String name, Level level) {
    ((Logger) LoggerFactory.getLogger(name)).setLevel(level);
  }
}
