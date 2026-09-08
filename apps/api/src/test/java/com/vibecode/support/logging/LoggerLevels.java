package com.vibecode.support.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;

/**
 * The explicit level of every logger in the JVM, taken as one value and put back as one value.
 *
 * <p>Logback's {@link LoggerContext} is JVM-wide and outlives every application context in the
 * test run. Spring Boot applies {@code logging.level.*} to it on each context refresh and never
 * unsets what a previous refresh applied, so a level a test class asked for is still in force for
 * every class that runs after it. Restoring one logger by hand is not enough, because a single
 * {@code @TestPropertySource} can name a dozen categories and the next one to be added will not be
 * in anybody's cleanup list.
 *
 * <p>What this covers is the explicit level on a logger — the field an inherited level cannot
 * override, and the only thing any of the routes below actually mutate. It does not cover
 * appenders, turbo filters, markers, or the pattern layouts, and it does not re-read the Logback
 * configuration: a snapshot is a copy of what was in force at the moment it was taken, so a
 * snapshot taken after something has already leaked will faithfully restore the leak.
 */
public final class LoggerLevels {

  /** Logger name to its explicit level, where a null value means the logger inherited its level. */
  private final Map<String, Level> levels;

  private LoggerLevels(Map<String, Level> levels) {
    this.levels = levels;
  }

  /** The explicit level of every logger that exists right now. */
  public static LoggerLevels snapshot() {
    Map<String, Level> levels = new LinkedHashMap<>();
    for (Logger logger : context().getLoggerList()) {
      levels.put(logger.getName(), logger.getLevel());
    }
    return new LoggerLevels(levels);
  }

  /**
   * Puts every logger that existed back to the level it had, and takes the raise off any logger
   * that was invented since.
   *
   * <p>The second half matters as much as the first. Naming a category in {@code logging.level.*}
   * creates its logger as a side effect, so the categories a test invents are precisely the ones a
   * put-back that only walked its own map would leave behind.
   *
   * <p>But an invented logger is not always a test's doing. The first Spring context built in a
   * JVM is what applies application.yml, and the levels that pin the categories this application
   * refuses to let print user data are invented at that moment too. Clearing those would be worse
   * than the leak: an isolation mechanism that silently unpins the logging fix is a false-negative
   * machine of its own, and this class did exactly that until the assertion in
   * {@code LoggingScenarios} caught it.
   *
   * <p>So an invented logger is cleared only when its level raises verbosity above what it would
   * otherwise inherit — which is what a leak looks like and what a pin never does. Three limits
   * follow, and none of them is harmless.
   *
   * <p>A level that is the same as or quieter than what it would inherit is kept. Quieter is the
   * obvious half; the same is the one that bites. A property route setting org.hibernate=DEBUG
   * under a DEBUG root is kept here, and then {@code LogCapture} raises the root to TRACE while
   * org.hibernate sits at DEBUG — a capture that sees less than it set up for, which is the
   * blindness this whole task exists to remove. It is second-order: it needs an upstream leak to
   * have got that far in the first place.
   *
   * <p>A configured level more verbose than its parent would be cleared. Only a genuine context
   * refresh puts it back, and a refresh is what a cache hit never is: Spring caches contexts and
   * {@code LoggingApplicationListener} fires on {@code ApplicationEnvironmentPreparedEvent}, which
   * a reused context does not raise. So a cleared level stays cleared for every class that reuses
   * that context, and comes back only when some class builds a context with a different key.
   *
   * <p>A pin at INFO under a baseline quieter than INFO would be cleared outright. Nothing in this
   * suite sets WARN, ERROR or OFF as a baseline, and if that changes it fails loudly rather than
   * quietly, so it is recorded here rather than guarded against.
   */
  public void restore() {
    List<Logger> invented = new ArrayList<>();
    for (Logger logger : context().getLoggerList()) {
      if (!levels.containsKey(logger.getName())) {
        invented.add(logger);
        continue;
      }
      Level original = levels.get(logger.getName());
      // Logback refuses a null level on the root logger, and the root is always in the snapshot
      // with a level of its own, so the only way to reach this guard is a snapshot from another
      // context. Skipping is the honest answer: there is nothing to put back.
      if (original == null && Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
        continue;
      }
      if (logger.getLevel() != original) {
        logger.setLevel(original);
      }
    }
    // Second pass, after the hierarchy above is back, so "what it would inherit" is the answer the
    // restored configuration gives rather than the leaked one.
    for (Logger logger : invented) {
      Level explicit = logger.getLevel();
      if (explicit == null) {
        continue;
      }
      logger.setLevel(null);
      if (explicit.toInt() >= logger.getEffectiveLevel().toInt()) {
        // Same as, or quieter than, the surrounding configuration: a restriction, not a raise.
        // The "same as" half is the one with a cost, and the class comment above says what it is.
        logger.setLevel(explicit);
      }
    }
  }

  /** The explicit level of one logger, or null when it inherits. Reads live state, not the copy. */
  public static Level explicitLevelOf(String loggerName) {
    return ((Logger) LoggerFactory.getLogger(loggerName)).getLevel();
  }

  private static LoggerContext context() {
    return (LoggerContext) LoggerFactory.getILoggerFactory();
  }
}
