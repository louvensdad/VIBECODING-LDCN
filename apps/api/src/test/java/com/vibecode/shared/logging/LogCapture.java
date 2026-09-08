package com.vibecode.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vibecode.support.logging.LoggerLevels;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures everything the application logs while a piece of work runs.
 *
 * <p>The root level is raised to TRACE rather than DEBUG. TRACE is not a level anyone should run in
 * production, and that is exactly the point: the guarantee under test is that the value never
 * reaches a log line even when an operator turns the noise all the way up. Loggers with a level of
 * their own — the four Hibernate categories pinned in application.yml — keep it, because an
 * explicit level on a logger is not overridden by the level of its parent. That asymmetry is the
 * whole fix, and this class is what makes it observable.
 */
final class LogCapture {

  private LogCapture() {}

  interface Work {
    void run();
  }

  /**
   * Runs {@code work} with the root logger at TRACE and returns every event it produced.
   *
   * <p>The whole level configuration is snapshotted, not just the root's. Raising the root is this
   * class's own change and easy to undo, but {@code work} is arbitrary application code and may
   * itself set a level; putting back every logger costs the same and leaves nothing to argue
   * about. Appenders are not part of the snapshot, so the capture appender is still detached by
   * hand below.
   */
  static List<ILoggingEvent> capturing(Work work) {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    LoggerLevels before = LoggerLevels.snapshot();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    root.setLevel(Level.TRACE);
    try {
      work.run();
    } finally {
      before.restore();
      root.detachAppender(appender);
      appender.stop();
    }
    return List.copyOf(appender.list);
  }

  /**
   * Every captured line that contains {@code fixture}, rendered with its logger name so a failure
   * says which category leaked rather than only that something did.
   */
  static List<String> occurrences(List<ILoggingEvent> events, String fixture) {
    List<String> hits = new ArrayList<>();
    for (ILoggingEvent event : events) {
      // The formatted message is what an appender writes; the throwable is the other half of the
      // line, and an exception carrying a value in its message leaks just as effectively.
      String line = event.getFormattedMessage() + " " + event.getThrowableProxy();
      if (line.contains(fixture)) {
        hits.add(event.getLoggerName() + " @" + event.getLevel() + ": " + line);
      }
    }
    return hits;
  }
}
