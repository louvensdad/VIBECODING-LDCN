package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.vibecode.identity.application.IdentityService;
import com.vibecode.identity.domain.EmailAddress;
import com.vibecode.identity.domain.PlatformRole;
import com.vibecode.identity.domain.User;
import com.vibecode.identity.infrastructure.UserRepository;
import com.vibecode.support.TestIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The one leak a level could not close.
 *
 * <p>The other tests in this package prove that nothing prints a value while a write succeeds. This
 * one is about the write that fails. A driver builds the value that caused the failure into the
 * message of the SQLException it throws, and Hibernate prints that message at ERROR — a level no
 * pin can reach, on an ordinary constraint violation, with no operator opting in. Before {@link
 * SqlErrorDetailTurboFilter} existed, both cases below produced the fixture verbatim in a captured
 * log line:
 *
 * <pre>
 * org.hibernate.engine.jdbc.spi.SqlExceptionHelper @ERROR: Value too long for column
 *   "display_name CHARACTER VARYING(80)": "'vc_sql_error_secret_zqxw_928472-------… (231)"
 * org.hibernate.engine.jdbc.spi.SqlExceptionHelper @ERROR: Unique index or primary key violation:
 *   "public.idx_users_email ON public.users(email NULLS LAST)
 *    VALUES ( 'vc_sql_error_secret_zqxw_928472-28b083ea@example.com' )"
 * </pre>
 *
 * <p>Every case here runs the same value down the same path twice, once where the write succeeds
 * and once where it is rejected. That is not symmetry for its own sake: the previous round of this
 * work passed three reviews because every probe sent valid input, and the leak lived on the path
 * where a request is refused. A probe that only sends valid input measures half the surface.
 */
@SpringBootTest
class SqlErrorLoggingTest {

  /**
   * Exclusive to this task, and synthetic. Long enough that a fragment of it is still unmistakable,
   * and short enough to fit the 64-character local part of an email address with a per-run suffix.
   */
  private static final String FIXTURE = "vc_sql_error_secret_zqxw_928472";

  /**
   * A truncated dump is still a disclosure, and H2 truncates: the over-length message printed the
   * first eighty characters of the value and stopped. Asserting on pieces is what makes the
   * difference between "the whole value is absent" and "nothing recognisable survived".
   */
  private static final List<String> FRAGMENTS = List.of("zqxw", "928472", "sql_error_secret");

  private static final String SQL_EXCEPTION_HELPER =
      "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";
  private static final String FILTER = SqlErrorDetailTurboFilter.class.getName();

  @Autowired IdentityService identity;
  @Autowired UserRepository users;

  @Test
  @DisplayName("An over-length value is not printed when the write is rejected, nor when it is not")
  void overLengthValue() {
    // Success path first, with the same value in the same column. display_name is VARCHAR(80), so
    // this one fits and is stored; if the capture below were leaking for some reason unrelated to
    // the failure, this case would say so.
    List<ILoggingEvent> accepted =
        LogCapture.capturing(() -> identity.register(freshEmail(), TestIdentity.PASSWORD, FIXTURE));
    assertOrmRan(accepted);
    assertNothingLeaked(accepted);
    assertThat(eventsFrom(accepted, FILTER))
        .as("nothing failed, so the filter had no reason to intervene")
        .isEmpty();

    // Failure path: the same column, a value that does not fit. H2 answers with the value itself.
    String tooLong = FIXTURE + "-".repeat(200);
    List<ILoggingEvent> rejected =
        LogCapture.capturing(
            () ->
                assertThatThrownBy(
                        () -> identity.register(freshEmail(), TestIdentity.PASSWORD, tooLong))
                    .as("the write must actually be rejected, or this case proves nothing")
                    .isInstanceOf(DataIntegrityViolationException.class));

    assertNothingLeaked(rejected);
    assertOperatorCanStillSee(rejected, "22001");
  }

  @Test
  @DisplayName("A conflicting key is not printed when the write is rejected, nor when it is not")
  void duplicateKey() {
    String email = FIXTURE + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

    // Success path: the fixture goes into the indexed column and the row is written.
    List<ILoggingEvent> accepted =
        LogCapture.capturing(
            () ->
                users.saveAndFlush(
                    new User(EmailAddress.of(email), "irrelevant-hash", "first", PlatformRole.USER)));
    assertOrmRan(accepted);
    assertNothingLeaked(accepted);
    assertThat(eventsFrom(accepted, FILTER))
        .as("nothing failed, so the filter had no reason to intervene")
        .isEmpty();

    // Failure path: the same value again, now colliding with the row above. H2 prints the whole
    // conflicting key; pgjdbc would print the server's DETAIL line, which says the same thing.
    List<ILoggingEvent> rejected =
        LogCapture.capturing(
            () ->
                assertThatThrownBy(
                        () ->
                            users.saveAndFlush(
                                new User(
                                    EmailAddress.of(email),
                                    "irrelevant-hash",
                                    "second",
                                    PlatformRole.USER)))
                    .as("the write must actually be rejected, or this case proves nothing")
                    .isInstanceOf(DataIntegrityViolationException.class));

    assertNothingLeaked(rejected);
    assertOperatorCanStillSee(rejected, "23505");
  }

  /**
   * The capture has to have seen a statement, or it proves nothing about the ORM. Borrowed from
   * HibernateValueLoggingTest, for the same reason it exists there.
   */
  private void assertOrmRan(List<ILoggingEvent> events) {
    assertThat(events)
        .as("no ORM statement was logged, so this capture proves nothing")
        .anyMatch(event -> event.getLoggerName().equals("org.hibernate.SQL"));
  }

  private void assertNothingLeaked(List<ILoggingEvent> events) {
    assertThat(occurrences(events, FIXTURE)).isEmpty();
    for (String fragment : FRAGMENTS) {
      assertThat(occurrences(events, fragment)).as("fragment %s", fragment).isEmpty();
    }
  }

  /**
   * A leak traded for blindness would be a different failure, not a fix. Three things have to
   * survive a rejected write: the vendor error code and SQLState from Hibernate's own line, the
   * filter's ERROR saying a statement failed, and the type of the exception that caused it.
   */
  private void assertOperatorCanStillSee(List<ILoggingEvent> events, String sqlState) {
    assertThat(eventsFrom(events, SQL_EXCEPTION_HELPER))
        .as("the SQLState line must survive: it is how an operator classifies the failure")
        .anyMatch(
            event ->
                event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("SQLState: " + sqlState));

    assertThat(eventsFrom(events, FILTER))
        .as("a withheld driver message must still be announced at ERROR")
        .anyMatch(
            event ->
                event.getLevel() == Level.ERROR
                    && event.getFormattedMessage().contains("driver's message was withheld"));

    // The driver's exception class survives in the redacted stack trace, with its SQLState beside
    // it. The class name is the driver's own — org.h2.jdbc.JdbcSQLDataException here, a
    // PSQLException on PostgreSQL — so the assertion is on the shape the filter guarantees.
    assertThat(rendered(events))
        .as("the exception type must still be identifiable from the log alone")
        .anyMatch(line -> line.contains("(message withheld, SQLState " + sqlState));
  }

  private static List<ILoggingEvent> eventsFrom(List<ILoggingEvent> events, String loggerName) {
    return events.stream().filter(event -> event.getLoggerName().equals(loggerName)).toList();
  }

  private static List<String> occurrences(List<ILoggingEvent> events, String needle) {
    List<String> hits = new ArrayList<>();
    for (ILoggingEvent event : events) {
      String line = render(event);
      if (line.contains(needle)) {
        hits.add(event.getLoggerName() + " @" + event.getLevel() + ": " + line);
      }
    }
    return hits;
  }

  private static List<String> rendered(List<ILoggingEvent> events) {
    return events.stream().map(SqlErrorLoggingTest::render).toList();
  }

  /**
   * Renders an event the way a stack-trace-printing appender would: the message, then every
   * throwable in the cause chain with its own message and frames.
   *
   * <p>Deliberately not {@code LogCapture.occurrences}, and this is the point of the method. That
   * helper appends {@code event.getThrowableProxy()}, whose {@code toString} is Logback's default
   * {@code ThrowableProxy@1f69937a} — an identity hash, never the exception's message. So a value
   * that reaches the log only inside an attached exception is invisible to it, which is exactly how
   * the leak this test covers travels. JpaTransactionManager attaches the exception rather than
   * printing it, and a capture scanned with that helper reports zero hits while the appender writes
   * the value in full. LogCapture belongs to another change in flight; this renderer stays here
   * until it can be fixed there.
   */
  private static String render(ILoggingEvent event) {
    StringBuilder out = new StringBuilder(event.getFormattedMessage());
    for (IThrowableProxy proxy = event.getThrowableProxy();
        proxy != null;
        proxy = proxy.getCause()) {
      out.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
      for (StackTraceElementProxy frame : proxy.getStackTraceElementProxyArray()) {
        out.append('\n').append(frame.getSTEAsString());
      }
    }
    return out.toString();
  }

  private static String freshEmail() {
    return "sql-error-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
  }
}
