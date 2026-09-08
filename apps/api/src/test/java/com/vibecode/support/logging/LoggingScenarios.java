package com.vibecode.support.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * The two halves of the ordering proof, written once so both orders assert the same thing.
 *
 * <p>Keeping them here rather than duplicating them into the two ordering classes is not only
 * tidiness: two copies of an assertion drift, and a drifted copy would make one direction of the
 * proof weaker than the other without anyone noticing which.
 *
 * <p>The class name deliberately does not end in {@code Test}. Surefire's default includes would
 * otherwise pick these up as top-level tests and run the raising half on its own, outside any
 * ordering, which is the one context in which it proves nothing.
 */
abstract class LoggingScenarios {

  /**
   * A category no production code logs to, so raising it cannot make any other test noisier and a
   * non-null level on it can only have come from this proof or from the Spring property route.
   */
  static final String PROBE = "com.vibecode.probe.loggerlevelisolation";

  /** A category the application pins in application.yml; here to prove a restore does not wipe it. */
  static final String PINNED = "org.hibernate.orm.jdbc.bind";

  /** Raised because it is what the real leaking class raises, and it has no level of its own. */
  static final String INHERITING = "org.hibernate";

  private LoggingScenarios() {}

  /**
   * A context with nothing in it, whose only job is to make Spring Boot read application.yml.
   *
   * <p>The observing half asserts that a pinned category is still pinned, and a pin only exists
   * once Boot's logging listener has applied it — which happens when some application context is
   * built, not when the JVM starts. Without a context of its own, that assertion would pass or
   * fail according to whether an unrelated Spring test happened to run first, which is the same
   * order dependence this whole task exists to remove.
   *
   * <p>It is a bare {@code @Configuration} with no auto-configuration and no web environment, and
   * the test context framework caches it, so at most one is built for the whole run.
   */
  @Configuration
  static class ApplicationConfigurationOnly {}

  /** Test A: turns the noise all the way up, on the same categories the real leak involves. */
  abstract static class RaisesLoggersToTrace {

    @Test
    @DisplayName("A: raises the probe, an inheriting category and the root logger to TRACE")
    void raisesLoggersToTrace() {
      set(PROBE, Level.TRACE);
      set(INHERITING, Level.TRACE);
      set(org.slf4j.Logger.ROOT_LOGGER_NAME, Level.TRACE);

      // Asserted rather than assumed: if a future Logback made these calls no-ops, the observing
      // half would start passing for a reason that has nothing to do with the restore working.
      assertThat(LoggerLevels.explicitLevelOf(PROBE)).isEqualTo(Level.TRACE);
      assertThat(LoggerLevels.explicitLevelOf(INHERITING)).isEqualTo(Level.TRACE);
      assertThat(LoggerLevels.explicitLevelOf(org.slf4j.Logger.ROOT_LOGGER_NAME))
          .isEqualTo(Level.TRACE);
    }

    private static void set(String name, Level level) {
      ((Logger) LoggerFactory.getLogger(name)).setLevel(level);
    }
  }

  /**
   * Test B: the configuration this suite is supposed to run under, whatever ran before it.
   *
   * <p>Subclasses carry {@code @SpringBootTest(classes = ApplicationConfigurationOnly.class)} so
   * the pinned category below has been pinned by something. That context is what makes the pin
   * assertion sound; it is not what makes the other three pass. Boot's listener only ever applies
   * the levels it is given, so a probe, an org.hibernate or a root left raised by an earlier class
   * survives a context being built and is still seen here.
   *
   * <p>The flip side, stated rather than glossed over: a leak on one of the pinned categories
   * would be re-pinned by the next context that is actually built, and would not be caught here.
   * Actually built, not merely used — a class reusing a cached context raises no environment-
   * prepared event and re-applies nothing, so between two genuine refreshes a cleared pin stays
   * cleared. The three categories asserted first are chosen because nothing re-applies them; the
   * pinned one is asserted last, and it is there to catch a restore that over-reaches rather than
   * one that under-reaches.
   */
  abstract static class ExpectsTheDefaultConfiguration {

    @Test
    @DisplayName("B: the probe and the inheriting category carry no level, and the root is INFO")
    void seesTheDefaultConfiguration() {
      assertThat(LoggerLevels.explicitLevelOf(PROBE))
          .as("a level on %s can only have been left behind by another test", PROBE)
          .isNull();
      assertThat(LoggerLevels.explicitLevelOf(INHERITING))
          .as("%s has no level in application.yml and must inherit one", INHERITING)
          .isNull();
      assertThat(LoggerLevels.explicitLevelOf(org.slf4j.Logger.ROOT_LOGGER_NAME))
          .as("the root level configured for the test run")
          .isEqualTo(Level.INFO);
      // The other direction of the same guarantee: restoring must not undo the configuration the
      // application itself asked for. A restore that cleared every level would pass the three
      // assertions above and silently unpin the categories the whole logging fix rests on.
      assertThat(LoggerLevels.explicitLevelOf(PINNED))
          .as("%s is pinned in application.yml and must survive any restore", PINNED)
          .isEqualTo(Level.INFO);
    }
  }
}
