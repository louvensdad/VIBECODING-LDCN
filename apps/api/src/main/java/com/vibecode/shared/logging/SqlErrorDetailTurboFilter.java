package com.vibecode.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

/**
 * Keeps the JDBC driver's own words out of the log file.
 *
 * <p>A driver builds the value that caused a failure into the message of the {@link SQLException}
 * it throws, and two mechanisms then carry that message to a log line. Both were reproduced against
 * this repository's own H2 test database before this class was written, using a fixture value in a
 * column it did not fit and in a column that already held it:
 *
 * <ul>
 *   <li>{@code org.hibernate.engine.jdbc.spi.SqlExceptionHelper} logs {@code
 *       SQLException.getMessage()} verbatim, at ERROR. H2 answered with {@code Value too long for
 *       column "display_name CHARACTER VARYING(80)": "'} followed by the value, and with the whole
 *       conflicting key inside a {@code Unique index or primary key violation} message. On
 *       PostgreSQL pgjdbc copies the server's DETAIL line into the same place, which carries the
 *       conflicting key for a unique violation and every column of the row for a check violation.
 *   <li>The exception travels, and anything that logs it with the throwable attached prints that
 *       same message in the stack trace. {@code JpaTransactionManager} does exactly that on
 *       rollback.
 * </ul>
 *
 * <p>No {@code logging.level} setting reaches the first of those: it is emitted at ERROR, on an
 * ordinary constraint violation, with nobody opting in. That is why the categories pinned in
 * application.yml leave it standing and why this filter exists. Logback consults a TurboFilter
 * <em>before</em> the level check, on every call, so it sees the event whatever the configured
 * levels are; and because it runs at the call site rather than at an appender, an appender added
 * later - a file, a JSON encoder, a shipper - inherits the same protection instead of having to be
 * configured for it.
 *
 * <p>Two rules, one per mechanism. Any event carrying a {@code SQLException} anywhere in its cause
 * chain is re-emitted with a copy of that chain that keeps every class name and every stack frame
 * and drops every message; and everything {@code SqlExceptionHelper} logs is denied except the
 * lines it builds out of driver <em>metadata</em> rather than driver prose. The second rule is
 * deny-by-default on purpose: written the other way round, as a list of the shapes known to leak,
 * it would pass whatever a future driver or Hibernate version invents. Written this way the failure
 * mode is a lost diagnostic, not a lost secret.
 *
 * <p>What survives, because trading a leak for blindness would fail this just as badly: the line
 * naming the vendor error code and the SQLState passes untouched, so an operator still learns that
 * a write failed and what category of failure it was - {@code 23505} is a unique violation,
 * {@code 22001} an over-length value. Every denial is replaced by a line of this filter's own, at
 * the level of the event it replaced, so an ERROR is never silently dropped. The redacted stack
 * trace still says which exception types were raised and which code path raised them. What none of
 * it gives back is the failing value.
 *
 * <p>Limits, stated plainly. Nothing here rewrites a message, because a TurboFilter cannot: it may
 * only allow or deny, and what it emits in place of a denial it composes itself. So a logger that
 * concatenates an exception into its own message string rather than attaching it as a throwable is
 * out of reach - {@code TransactionInterceptor} does that on every rollback, which is why it is
 * pinned in application.yml alongside the other categories rather than handled here. And the
 * exception itself is untouched: this filter governs what is logged, not what is thrown, so any
 * code that reads {@code getMessage()} and puts it somewhere else still has the driver's text.
 */
public final class SqlErrorDetailTurboFilter extends TurboFilter {

  /** The one Hibernate class that prints {@code SQLException.getMessage()} to the log. */
  static final String SQL_EXCEPTION_HELPER = "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

  /**
   * The lines SqlExceptionHelper builds out of driver metadata rather than driver prose: the vendor
   * error code and the SQLState, for an error and for a warning. Both are a number and a short
   * alphanumeric code, so the pattern is exact enough that anything else from this logger -
   * including a driver message that happens to open with the same words - falls through to the
   * denial.
   */
  private static final Pattern VENDOR_CODE_ONLY =
      Pattern.compile("^SQL (Error|Warning Code): -?\\d+, SQLState: (\\w*|null)$");

  /**
   * Where the replacement lines go. A logger of its own rather than the name of the logger being
   * replaced, so that grepping for it finds every place this filter intervened and so that a
   * replacement can never be mistaken for something the framework said. The original logger name is
   * carried in the message instead.
   */
  private static final org.slf4j.Logger REPLACEMENT =
      LoggerFactory.getLogger(SqlErrorDetailTurboFilter.class);

  /** Guards against a chain that loops back on itself, and against a pathological depth. */
  private static final int MAX_CAUSE_DEPTH = 32;

  @Override
  public FilterReply decide(
      Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {

    boolean fromSqlExceptionHelper =
        logger != null && SQL_EXCEPTION_HELPER.equals(logger.getName());
    // The fast path, and it is nearly every event in the application: no throwable to inspect and
    // not the one logger this filter judges. Nothing is allocated before this returns, because a
    // TurboFilter runs on every logging call including the ones the level would discard.
    if (t == null && !fromSqlExceptionHelper) {
      return FilterReply.NEUTRAL;
    }

    if (firstSqlExceptionIn(t) != null) {
      // Rule one, and it applies to every logger because the exception reaches every logger that
      // handles it. The message the caller wrote is its own, not the driver's, so it is passed
      // through unchanged; what is replaced is the throwable, whose messages are the driver's all
      // the way down - Hibernate's own ConstraintViolationException embeds the driver's text in its
      // message too, which is why the copy keeps no message at any depth rather than only at the
      // SQLException.
      logAt(level, name(logger) + ": " + format, params, redactMessages(t));
      return FilterReply.DENY;
    }

    if (!fromSqlExceptionHelper) {
      return FilterReply.NEUTRAL;
    }

    // A null format is not a log call. Logback routes isWarnEnabled(), isErrorEnabled() and their
    // siblings through this same method with every argument null, and a DENY there answers "no" to
    // the caller. Hibernate asks exactly those questions before it writes anything: an earlier
    // version of this filter denied them, which silenced the SQLState line this filter exists to
    // preserve and made it emit a replacement for a question rather than for a message.
    if (format == null) {
      return FilterReply.NEUTRAL;
    }

    if (VENDOR_CODE_ONLY.matcher(format).matches()) {
      return FilterReply.NEUTRAL;
    }

    // Rule two. Everything else this logger writes is the driver's own message with no throwable
    // attached: at ERROR for a failed statement, at WARN for a SQLWarning.
    logAt(
        level,
        "A JDBC statement failed and the driver's message was withheld: drivers build the value"
            + " that caused the failure into it. The vendor error code and SQLState are on the"
            + " adjacent SqlExceptionHelper line, and the exception itself still reaches the"
            + " caller.",
        null,
        null);
    return FilterReply.DENY;
  }

  private static String name(Logger logger) {
    return logger == null ? "unknown logger" : logger.getName();
  }

  /**
   * Emits at the level of the denied event. Nothing here can recurse: the replacement carries no
   * SQLException and is not logged on the Hibernate logger, so a second pass through {@link
   * #decide} returns NEUTRAL immediately.
   */
  private static void logAt(Level level, String message, Object[] params, Throwable throwable) {
    Object[] arguments = argumentsFor(params, throwable);
    if (level == null) {
      REPLACEMENT.error(message, arguments);
      return;
    }
    switch (level.toInt()) {
      case Level.ERROR_INT -> REPLACEMENT.error(message, arguments);
      case Level.WARN_INT -> REPLACEMENT.warn(message, arguments);
      case Level.INFO_INT -> REPLACEMENT.info(message, arguments);
      case Level.TRACE_INT -> REPLACEMENT.trace(message, arguments);
      default -> REPLACEMENT.debug(message, arguments);
    }
  }

  /** SLF4J takes a trailing throwable as the throwable rather than as a placeholder argument. */
  private static Object[] argumentsFor(Object[] params, Throwable throwable) {
    List<Object> arguments = new ArrayList<>();
    if (params != null) {
      for (Object param : params) {
        arguments.add(param);
      }
    }
    if (throwable != null) {
      arguments.add(throwable);
    }
    return arguments.toArray();
  }

  /**
   * A copy of the cause chain with every message removed and every stack frame kept. The class name
   * and, for a SQLException, the SQLState and vendor code are what an operator actually needs to
   * classify the failure; the message is the only part the driver wrote out of the failing value.
   */
  private static Throwable redactMessages(Throwable original) {
    List<Throwable> chain = chainOf(original);
    Throwable copy = null;
    for (int i = chain.size() - 1; i >= 0; i--) {
      Throwable link = chain.get(i);
      copy = new RedactedThrowable(describe(link), copy, link.getStackTrace());
    }
    return copy;
  }

  private static String describe(Throwable link) {
    String description = link.getClass().getName() + " (message withheld";
    if (link instanceof SQLException sql) {
      description += ", SQLState " + sql.getSQLState() + ", vendor code " + sql.getErrorCode();
    }
    return description + ")";
  }

  private static List<Throwable> chainOf(Throwable original) {
    List<Throwable> chain = new ArrayList<>();
    Throwable current = original;
    while (current != null && chain.size() < MAX_CAUSE_DEPTH && !chain.contains(current)) {
      chain.add(current);
      current = current.getCause();
    }
    return chain;
  }

  private static SQLException firstSqlExceptionIn(Throwable t) {
    for (Throwable link : chainOf(t)) {
      if (link instanceof SQLException sql) {
        return sql;
      }
    }
    return null;
  }

  /**
   * Carries frames and a class name and nothing else. It is not thrown and never leaves the logging
   * path; its only purpose is to give an appender something to render in place of the original.
   */
  private static final class RedactedThrowable extends Throwable {

    private static final long serialVersionUID = 1L;

    private RedactedThrowable(String message, Throwable cause, StackTraceElement[] frames) {
      super(message, cause, false, true);
      setStackTrace(frames);
    }

    /**
     * The class name is already the whole message, so the usual {@code ClassName: message} prefix
     * would print it twice.
     */
    @Override
    public String toString() {
      return getMessage();
    }
  }
}
