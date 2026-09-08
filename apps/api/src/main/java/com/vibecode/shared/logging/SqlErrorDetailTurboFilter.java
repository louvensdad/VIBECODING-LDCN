package com.vibecode.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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
 *   <li>The exception travels, and anything that logs it prints that same message — in the stack
 *       trace when the throwable is attached, in the message itself when a caller concatenates it.
 *       {@code JpaTransactionManager} attaches it on rollback.
 * </ul>
 *
 * <p>No {@code logging.level} setting reaches the first of those: it is emitted at ERROR, on an
 * ordinary constraint violation, with nobody opting in. That is why the categories pinned in
 * application.yml leave it standing and why this filter exists. Logback consults a TurboFilter
 * <em>before</em> the level check, on every call, so it sees the event whatever the configured
 * levels are; and because it runs at the call site rather than at an appender, an appender added
 * later — a file, a JSON encoder, a shipper — inherits the same protection instead of having to be
 * configured for it.
 *
 * <p>Two rules, one per mechanism.
 *
 * <ol>
 *   <li>Any event carrying a {@code SQLException} — attached as the throwable, or passed among the
 *       arguments, which is where SLF4J's varargs overloads leave it at the moment a TurboFilter
 *       runs — is denied. In its place goes fixed text of this filter's own and a copy of the cause
 *       and suppressed chains that keeps every class name and every stack frame and drops the
 *       messages. The caller's own message is <em>not</em> reprinted: a caller that concatenates
 *       {@code ex.getMessage()} into its format string and also attaches the exception would
 *       otherwise have the driver's text reprinted here, under this filter's name, by the very code
 *       meant to withhold it.
 *   <li>Everything {@code SqlExceptionHelper} logs is denied except the lines it builds out of
 *       driver <em>metadata</em> rather than driver prose.
 * </ol>
 *
 * <p>Both are deny-by-default on purpose: written the other way round, as a list of the shapes known
 * to leak, they would pass whatever a future driver or Hibernate version invents. Written this way
 * the failure mode is a lost diagnostic, not a lost secret.
 *
 * <p>What survives, because trading a leak for blindness would fail this just as badly: the line
 * naming the vendor error code and the SQLState passes untouched, so an operator still learns that
 * a write failed and what category of failure it was — {@code 23505} is a unique violation,
 * {@code 22001} an over-length value. Every denial is replaced by a line of this filter's own, at
 * the level and with the marker of the event it replaced, naming the logger that was denied. The
 * redacted trace still says which exception types were raised and which code path raised them. The
 * statement itself is still logged, unfiltered, by {@code org.hibernate.SQL}, which prints it with
 * placeholders rather than values and is deliberately left on. And a failing migration keeps the
 * fields of Flyway's report that Flyway itself wrote — see {@link #DEVELOPER_AUTHORED}. What none
 * of it gives back is the failing value.
 *
 * <p>Limits, stated plainly. A TurboFilter may only allow or deny, never rewrite, so a logger that
 * concatenates an exception into its own message string and attaches nothing is out of reach:
 * nothing marks the event as carrying driver text, and scanning message content for it would be
 * guesswork. {@code TransactionInterceptor} does exactly that on every rollback, which is why it is
 * pinned in application.yml rather than handled here. And the exception itself is untouched: this
 * filter governs what is logged, not what is thrown, so code that reads {@code getMessage()} and
 * puts it somewhere else still has the driver's text.
 */
public final class SqlErrorDetailTurboFilter extends TurboFilter {

  /** The one Hibernate class that prints {@code SQLException.getMessage()} to the log. */
  static final String SQL_EXCEPTION_HELPER = "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

  /**
   * The lines SqlExceptionHelper builds out of driver metadata rather than driver prose: the vendor
   * error code and the SQLState, for an error and for a warning. Both are a number and a short
   * alphanumeric code, so the pattern is exact enough that anything else from this logger —
   * including a driver message that happens to open with the same words — falls through to the
   * denial.
   */
  private static final Pattern VENDOR_CODE_ONLY =
      Pattern.compile("^SQL (Error|Warning Code): -?\\d+, SQLState: (\\w*|null)$");

  /**
   * The one kind of exception whose message is kept, and the reason is whose words it is.
   *
   * <p>A failing migration is the case where withholding costs most and protects least. Flyway's
   * message names the script, the file and the line, the SQLState and the vendor code — all of it
   * developer-written or metadata, none of it user input — and without it a failed startup says
   * only that some statement somewhere was rejected.
   *
   * <p>Kept line by line rather than whole, because Flyway builds the driver's message <em>into</em>
   * its own. Verified against Flyway 10.20.1 and H2 before this was written: a migration inserting
   * an over-length value produced
   * {@code Message    : Value too long for column "LABEL CHARACTER VARYING(10)": "'<the value>'} as
   * one field of an otherwise safe report, with the failing statement echoed on the continuation
   * line. Allowlisting the whole message would therefore have reprinted exactly what the nested
   * SQLException was being withheld for.
   *
   * <p>Matched by simple name inside the {@code org.flywaydb} package rather than by fully
   * qualified name, because the fully qualified name is not stable: {@code FlywayMigrateException}
   * moved from {@code org.flywaydb.core.internal.command.DbMigrate$FlywayMigrateException} to
   * {@code org.flywaydb.core.internal.exception.FlywayMigrateException} in Flyway 10.20. A FQCN
   * allowlist would have stopped matching at that upgrade without anything failing.
   */
  private static final Set<String> DEVELOPER_AUTHORED =
      Set.of("FlywayMigrateException", "FlywaySqlScriptException");

  private static final String FLYWAY_PACKAGE = "org.flywaydb.";

  /**
   * The fields of Flyway's report that are safe to print. An allowlist, like everything else here:
   * the {@code Message} field is the driver's, and so is the unlabelled line that continues it, and
   * both fall through to being dropped. If a future Flyway renames a field the line disappears from
   * the log rather than the driver's text appearing in it.
   */
  private static final Pattern FLYWAY_SAFE_LINE =
      Pattern.compile(
          "^\\s*(|-+|Script .*|Migration .*|SQL State\\s*:.*|Error Code\\s*:.*|Location\\s*:.*"
              + "|Line\\s*:.*|Statement\\s*:.*)\\s*$");

  private static final String FLYWAY_WITHHELD_FIELD =
      "Message    : (withheld: the driver builds the failing value into it)";

  /**
   * Where the replacement lines go. A logger of its own rather than the name of the logger being
   * replaced, so that grepping for it finds every place this filter intervened and so that a
   * replacement can never be mistaken for something the framework said. The original logger name is
   * carried in the message instead.
   */
  private static final org.slf4j.Logger REPLACEMENT =
      LoggerFactory.getLogger(SqlErrorDetailTurboFilter.class);

  @Override
  public FilterReply decide(
      Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {

    boolean fromSqlExceptionHelper =
        logger != null && SQL_EXCEPTION_HELPER.equals(logger.getName());

    // SLF4J treats a trailing Throwable argument as the throwable, but it does that when it builds
    // the event — which is after this filter runs. So for log.error("failed: {}", id, ex), the
    // commonest shape in Java, `t` is null here and the exception is sitting in `params`. Reading
    // only `t` made this filter fail open on that shape, which is the opposite of what a
    // deny-by-default control is for.
    Throwable carried = t != null ? t : firstThrowableIn(params);

    // The fast path, and it is nearly every event in the application: nothing to inspect and not
    // the one logger this filter judges. Nothing is allocated before this returns, because a
    // TurboFilter runs on every logging call including the ones the level would discard.
    if (carried == null && !fromSqlExceptionHelper) {
      return FilterReply.NEUTRAL;
    }

    if (carried != null && firstSqlExceptionIn(carried) != null) {
      logAt(
          marker,
          level,
          "A logging call carried a SQLException and was withheld: drivers build the value that"
              + " caused the failure into its message, and a caller may have built that message"
              + " into its own. The denied logger was "
              + name(logger)
              + ". Exception types, SQLState and frames are below; the statement itself is logged"
              + " by org.hibernate.SQL with placeholders rather than values.",
          redactMessages(carried));
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
        marker,
        level,
        "A JDBC statement failed and the driver's message was withheld: drivers build the value"
            + " that caused the failure into it. The vendor error code and SQLState are on the"
            + " adjacent SqlExceptionHelper line, and the exception itself still reaches the"
            + " caller.",
        null);
    return FilterReply.DENY;
  }

  private static String name(Logger logger) {
    return logger == null ? "an unnamed logger" : logger.getName();
  }

  /**
   * Emits at the level of the denied event, carrying its marker so that an appender routing on
   * markers still sees the replacement. Nothing here can recurse: the replacement carries no
   * SQLException and is not logged on the Hibernate logger, so a second pass through {@link
   * #decide} returns NEUTRAL immediately.
   */
  private static void logAt(Marker marker, Level level, String message, Throwable throwable) {
    int severity = level == null ? Level.ERROR_INT : level.toInt();
    switch (severity) {
      case Level.ERROR_INT -> REPLACEMENT.error(marker, message, throwable);
      case Level.WARN_INT -> REPLACEMENT.warn(marker, message, throwable);
      case Level.INFO_INT -> REPLACEMENT.info(marker, message, throwable);
      case Level.TRACE_INT -> REPLACEMENT.trace(marker, message, throwable);
      default -> REPLACEMENT.debug(marker, message, throwable);
    }
  }

  /** The first throwable among a logging call's arguments, wherever in the array it sits. */
  private static Throwable firstThrowableIn(Object[] params) {
    if (params == null) {
      return null;
    }
    for (Object param : params) {
      if (param instanceof Throwable throwable) {
        return throwable;
      }
    }
    return null;
  }

  /**
   * A copy of the throwable with every message removed except the developer-authored ones, and
   * every stack frame and suppressed exception kept.
   *
   * <p>Suppressed exceptions are copied rather than dropped for two reasons that pull the same way:
   * a try-with-resources failure during rollback carries the same value out by that door, and
   * {@code ThrowableProxy} renders the list, so a copy that omitted it would both miss a leak and
   * lose a diagnostic.
   */
  private static Throwable redactMessages(Throwable original) {
    return copyOf(original, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  private static Throwable copyOf(Throwable original, Set<Throwable> seen) {
    // A cause chain is a graph, not a list: a self-referencing or mutually-referencing pair would
    // otherwise recurse until the stack ran out, turning a redaction into a crash.
    if (original == null || !seen.add(original)) {
      return null;
    }
    RedactedThrowable copy =
        new RedactedThrowable(
            describe(original), copyOf(original.getCause(), seen), original.getStackTrace());
    for (Throwable suppressed : original.getSuppressed()) {
      Throwable suppressedCopy = copyOf(suppressed, seen);
      if (suppressedCopy != null) {
        copy.addSuppressed(suppressedCopy);
      }
    }
    return copy;
  }

  private static String describe(Throwable link) {
    String className = link.getClass().getName();
    if (isDeveloperAuthored(link)) {
      return className + ": " + keepSafeFlywayFields(link.getMessage());
    }
    String description = className + " (message withheld";
    if (link instanceof SQLException sql) {
      description += ", SQLState " + sql.getSQLState() + ", vendor code " + sql.getErrorCode();
    }
    return description + ")";
  }

  /**
   * Flyway's report with every field it did not write itself removed. One replacement line marks
   * the gap, so a reader sees that something was withheld rather than wondering why the report is
   * short.
   */
  private static String keepSafeFlywayFields(String message) {
    if (message == null) {
      return "(no message)";
    }
    StringBuilder kept = new StringBuilder();
    boolean noted = false;
    for (String line : message.split("\\R")) {
      if (FLYWAY_SAFE_LINE.matcher(line).matches()) {
        kept.append(line).append(System.lineSeparator());
      } else if (!noted) {
        kept.append(FLYWAY_WITHHELD_FIELD).append(System.lineSeparator());
        noted = true;
      }
    }
    return kept.toString();
  }

  private static boolean isDeveloperAuthored(Throwable link) {
    Class<?> type = link.getClass();
    return type.getName().startsWith(FLYWAY_PACKAGE)
        && DEVELOPER_AUTHORED.contains(type.getSimpleName());
  }

  /**
   * Whether a SQLException is anywhere in the throwable's cause or suppressed graph.
   *
   * <p>Deliberately without a depth limit. An earlier version stopped after thirty-two links, which
   * meant a SQLException deeper than that went undetected and the whole throwable was logged in
   * full — a guard that disabled the control it was guarding. Termination comes from the identity
   * set instead, which is what the actual hazard was.
   */
  private static SQLException firstSqlExceptionIn(Throwable original) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    List<Throwable> pending = new ArrayList<>();
    pending.add(original);
    while (!pending.isEmpty()) {
      Throwable current = pending.remove(pending.size() - 1);
      if (current == null || !seen.add(current)) {
        continue;
      }
      if (current instanceof SQLException sql) {
        return sql;
      }
      pending.add(current.getCause());
      Collections.addAll(pending, current.getSuppressed());
    }
    return null;
  }

  /**
   * Carries frames, a class name and a suppressed list, and nothing else. It is not thrown and
   * never leaves the logging path; its only purpose is to give an appender something to render in
   * place of the original. Suppression is enabled so the copied list survives.
   */
  private static final class RedactedThrowable extends Throwable {

    private static final long serialVersionUID = 1L;

    private RedactedThrowable(String message, Throwable cause, StackTraceElement[] frames) {
      super(message, cause, true, true);
      setStackTrace(frames);
    }

    /**
     * The class name is already part of the message, so {@code printStackTrace} would otherwise
     * print it twice. Logback renders the proxy's class name and message separately and is
     * unaffected either way.
     */
    @Override
    public String toString() {
      return getMessage();
    }
  }
}
