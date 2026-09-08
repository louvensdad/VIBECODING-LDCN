package com.vibecode.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.vibecode.support.logging.LoggerLevels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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
 *
 * <p>Public only so far as it has to be. {@link #lineOf} is what the leak tests in other modules
 * need, and they need it from here rather than from a copy of their own: the throwable half of a
 * log line was rendered wrongly in three places at once, which is what happens when three files
 * each write their own two-line version of the same thing. The capture itself stays
 * package-private, because raising the root logger is this package's business.
 */
public final class LogCapture {

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
      String line = lineOf(event);
      if (line.contains(fixture)) {
        hits.add(event.getLoggerName() + " @" + event.getLevel() + ": " + line);
      }
    }
    return hits;
  }

  /**
   * One captured event as the text an appender would write: the formatted message, then every
   * throwable attached to it.
   *
   * <p>The throwable half is not decoration. A failed database write puts the offending value in
   * the driver's exception message, and that message travels wherever the exception does — logged
   * as text by one category, as an attachment by the next. A helper that reads only the formatted
   * message reports zero hits while the appender writes the value in full.
   *
   * <p>This method exists because appending the proxy itself did exactly that.
   * {@code ThrowableProxy} has no {@code toString}, so {@code "" + event.getThrowableProxy()}
   * yielded {@code ThrowableProxy@1f69937a} and never the message — a scan that claimed to cover
   * exceptions and covered nothing.
   */
  public static String lineOf(ILoggingEvent event) {
    StringBuilder line = new StringBuilder(event.getFormattedMessage());
    append(line, event.getThrowableProxy(), Collections.newSetFromMap(new IdentityHashMap<>()));
    return line.toString();
  }

  /**
   * Appends one throwable and everything hanging off it: the cause chain, because a wrapped
   * exception keeps the original message, and the suppressed list, because a try-with-resources
   * failure during rollback carries the same value out by a different door.
   *
   * <p>{@code seen} is not theoretical tidiness. A cause chain is a graph, not a list, and a
   * mutually-referencing pair would otherwise recurse until the stack ran out — turning a leak
   * check into a crash.
   */
  private static void append(StringBuilder into, IThrowableProxy throwable, Set<IThrowableProxy> seen) {
    if (throwable == null || !seen.add(throwable)) {
      return;
    }
    into.append(' ').append(throwable.getClassName()).append(": ").append(throwable.getMessage());
    append(into, throwable.getCause(), seen);
    IThrowableProxy[] suppressed = throwable.getSuppressed();
    if (suppressed != null) {
      for (IThrowableProxy each : suppressed) {
        append(into, each, seen);
      }
    }
  }
}
