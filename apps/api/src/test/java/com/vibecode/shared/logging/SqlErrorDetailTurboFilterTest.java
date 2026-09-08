package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The shapes a leak can take, rather than the paths it currently takes.
 *
 * <p>{@link SqlErrorLoggingTest} proves the filter on the two real database failures that reach it
 * today. This one is about the shapes it must survive tomorrow: a caller that concatenates an
 * exception message into its own, the SLF4J varargs overload that leaves the throwable among the
 * arguments, a SQLException hidden in a suppressed list or far down a cause chain, and a failing
 * migration whose report has to survive while the driver's half of it does not. None of these is
 * live in this codebase — every exception-carrying log call in src/main/java uses the two-argument
 * form — and that is exactly why they are asserted rather than assumed. A deny-by-default control
 * that fails open on the commonest idiom in Java is not deny-by-default.
 *
 * <p>The SQLException here is constructed rather than provoked. The end-to-end proof that a real
 * driver builds a real value into a real message lives in {@link SqlErrorLoggingTest}; what is under
 * test here is the filter's reading of the event, and a synthetic exception makes the shape the
 * variable rather than the database.
 */
@SpringBootTest
class SqlErrorDetailTurboFilterTest {

  private static final String FIXTURE = "vc_sql_error_secret_zqxw_928472";

  /** Shaped like what H2 and pgjdbc actually produce, with the value built into the message. */
  private static SQLException driverFailure() {
    return new SQLException(
        "Value too long for column \"label CHARACTER VARYING(10)\": \"'" + FIXTURE + "' (31)\"",
        "22001",
        22001);
  }

  private final Logger caller = LoggerFactory.getLogger("com.vibecode.example.SomeService");

  @Test
  @DisplayName("A caller that concatenates the driver's message into its own is denied")
  void concatenatedIntoTheCallersOwnMessage() {
    SQLException failure = driverFailure();

    // The shape that made re-emitting the caller's format unsafe: rule one fires on the attached
    // throwable, redacts the trace, and would then have reprinted the driver's text — under this
    // filter's own logger name — because the caller had already built it into the format string.
    List<ILoggingEvent> events =
        LogCapture.capturing(() -> caller.error("Save rejected: " + failure.getMessage(), failure));

    assertNothingLeaked(events);
    assertReplacementNames(events, "com.vibecode.example.SomeService");
  }

  @Test
  @DisplayName("The SLF4J varargs overload, where the throwable is an argument and not the cause")
  void varargsOverloadWithATrailingThrowable() {
    SQLException failure = driverFailure();

    // Logback consults the TurboFilter before SLF4J extracts a trailing throwable, so `t` is null
    // here and the exception is in `params`. Reading only `t` let this shape straight through.
    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> caller.error("Save rejected: {}", failure.getMessage(), failure));

    assertNothingLeaked(events);
    assertReplacementNames(events, "com.vibecode.example.SomeService");
  }

  @Test
  @DisplayName("A safe message with the exception as a trailing argument is denied too")
  void varargsOverloadWithASafeMessage() {
    SQLException failure = driverFailure();
    List<ILoggingEvent> events =
        LogCapture.capturing(() -> caller.warn("Save rejected for {}", UUID.randomUUID(), failure));

    assertNothingLeaked(events);
    assertReplacementNames(events, "com.vibecode.example.SomeService");
  }

  @Test
  @DisplayName("A SQLException attached as suppressed rather than as a cause is found")
  void suppressedSqlException() {
    // The shape try-with-resources produces when close() fails alongside a primary error: the
    // SQLException is on neither the throwable nor its cause chain, and ThrowableProxy renders it.
    RuntimeException primary = new RuntimeException("rollback failed");
    primary.addSuppressed(driverFailure());

    List<ILoggingEvent> events =
        LogCapture.capturing(() -> caller.error("Could not close the session", primary));

    assertNothingLeaked(events);
  }

  @Test
  @DisplayName("Suppressed exceptions survive redaction instead of being dropped from the trace")
  void suppressedEntriesAreKeptInTheRedactedTrace() {
    RuntimeException primary = new RuntimeException("rollback failed", driverFailure());
    primary.addSuppressed(new IllegalStateException("connection already closed"));

    List<ILoggingEvent> events =
        LogCapture.capturing(() -> caller.error("Could not close the session", primary));

    assertNothingLeaked(events);
    // Redaction removes messages, not structure. A copy that dropped the suppressed list would
    // both miss the leak above and quietly lose a diagnostic.
    assertThat(renderedLines(events))
        .as("the suppressed exception must still appear in the redacted trace")
        .anyMatch(line -> line.contains("java.lang.IllegalStateException (message withheld)"));
    assertThat(renderedLines(events))
        .as("and the SQLState of the cause must still be readable")
        .anyMatch(line -> line.contains("(message withheld, SQLState 22001"));
  }

  @Test
  @DisplayName("A SQLException far down a long cause chain is still found")
  void deeplyNestedSqlException() {
    // Forty links, where the depth guard used to stop at thirty-two — and stopping meant not
    // finding, which meant logging the whole thing verbatim. A guard that fails open is worse than
    // no guard, because it reads like protection.
    Throwable wrapped = driverFailure();
    for (int i = 0; i < 40; i++) {
      wrapped = new IllegalStateException("layer " + i, wrapped);
    }
    Throwable outermost = wrapped;

    List<ILoggingEvent> events =
        LogCapture.capturing(() -> caller.error("Deeply wrapped failure", outermost));

    assertNothingLeaked(events);
  }

  @Test
  @DisplayName("A cycle in the cause chain terminates instead of exhausting the stack")
  void cyclicCauseChain() {
    SQLException first = driverFailure();
    SQLException second = new SQLException("second", "22001", 22001);
    first.initCause(second);
    second.initCause(first);

    List<ILoggingEvent> events =
        LogCapture.capturing(() -> caller.error("Cyclic failure", first));

    assertNothingLeaked(events);
  }

  @Test
  @DisplayName("The marker of the denied event is carried to its replacement")
  void markerIsPreserved() {
    Marker marker = MarkerFactory.getMarker("AUDIT");
    SQLException failure = driverFailure();

    List<ILoggingEvent> events =
        LogCapture.capturing(() -> caller.error(marker, "Save rejected", failure));

    assertNothingLeaked(events);
    // An appender routing on markers would otherwise lose the replaced event entirely.
    assertThat(replacements(events))
        .as("the replacement must carry the marker the denied event had")
        .anyMatch(event -> event.getMarkerList() != null
            && event.getMarkerList().stream().anyMatch(m -> m.contains(marker)));
  }

  @Test
  @DisplayName("A failing migration keeps Flyway's own fields and loses the driver's")
  void failingMigrationKeepsWhatFlywayWrote(@TempDir Path migrations) throws Exception {
    // The data-backfill shape, and it is the only one that separates the two halves. A migration
    // with the value written into it as a literal proves nothing: the literal is developer-authored
    // SQL that Flyway's report is meant to print, so it would appear whether the driver's message
    // was withheld or not. Here the value lives only in a row seeded outside the migration, the
    // migration copies rows without naming any of them, and the driver is the only thing that can
    // put the value into a log line.
    String url = "jdbc:h2:mem:sqlerrorfilter-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    try (Connection seed = DriverManager.getConnection(url, "sa", "");
        Statement statement = seed.createStatement()) {
      statement.execute("CREATE TABLE seed (label VARCHAR(100) NOT NULL)");
      statement.execute("INSERT INTO seed VALUES ('" + FIXTURE + "')");
    }

    Files.writeString(
        migrations.resolve("V2__probe.sql"),
        """
        CREATE TABLE target (label VARCHAR(100) NOT NULL);
        CREATE UNIQUE INDEX idx_target_label ON target(label);
        INSERT INTO target SELECT label FROM seed;
        INSERT INTO target SELECT label FROM seed;
        """);

    // A database of this test's own, discarded with the JVM. Nothing here touches the application's
    // schema, its migrations or its datasource.
    Flyway flyway =
        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:" + migrations.toAbsolutePath())
            // The seed row above leaves the schema non-empty, which Flyway otherwise refuses to
            // migrate into. Nothing to do with the filter; without it the run fails before any
            // statement executes.
            .baselineOnMigrate(true)
            .load();

    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> {
              try {
                flyway.migrate();
                throw new AssertionError("the migration was supposed to fail");
              } catch (RuntimeException failure) {
                // What Spring Boot does with a startup failure, and the reason the filter has to be
                // installed before the context finishes starting.
                caller.error("Migration failed", failure);
              }
            });

    // Flyway folds the driver's message into its own report, as a field named "Message". Verified
    // against Flyway 10.20.1: allowlisting the whole message, rather than the fields Flyway itself
    // writes, would have reprinted the value the nested SQLException was being withheld for.
    assertNothingLeaked(events);

    List<String> lines = renderedLines(events);
    assertThat(lines)
        .as("the migration script must still be named")
        .anyMatch(line -> line.contains("V2__probe.sql"));
    assertThat(lines)
        .as("the line number within the migration must survive")
        .anyMatch(line -> line.contains("Line       : 4"));
    assertThat(lines)
        .as("the failing statement must survive: it is developer-written and reviewed")
        .anyMatch(line -> line.contains("Statement  : INSERT INTO target SELECT label FROM seed"));
    assertThat(lines)
        .as("the SQLState must survive")
        .anyMatch(line -> line.contains("SQL State  : 23505"));
    assertThat(lines)
        .as("and the gap where the driver's field was must be visible, not silent")
        .anyMatch(line -> line.contains("Message    : (withheld"));
    assertThat(lines)
        .as("the driver's own words must not be among what survived")
        .noneMatch(line -> line.contains("Unique index or primary key violation"));
  }

  private void assertNothingLeaked(List<ILoggingEvent> events) {
    assertThat(LogCapture.occurrences(events, FIXTURE)).isEmpty();
    assertThat(LogCapture.occurrences(events, "928472")).isEmpty();
    assertThat(LogCapture.occurrences(events, "zqxw")).isEmpty();
  }

  /** The denial has to be announced, and it has to say which logger was denied. */
  private void assertReplacementNames(List<ILoggingEvent> events, String deniedLogger) {
    assertThat(replacements(events))
        .as("a denied event must be replaced by one naming the logger it came from")
        .anyMatch(event -> event.getFormattedMessage().contains(deniedLogger));
  }

  private static List<ILoggingEvent> replacements(List<ILoggingEvent> events) {
    String filter = SqlErrorDetailTurboFilter.class.getName();
    return events.stream().filter(event -> event.getLoggerName().equals(filter)).toList();
  }

  private static List<String> renderedLines(List<ILoggingEvent> events) {
    return events.stream().map(LogCapture::lineOf).toList();
  }
}
