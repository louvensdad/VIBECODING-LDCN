package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.vibecode.support.logging.LoggerLevelIsolation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scanner, checked against the case it used to miss entirely.
 *
 * <p>{@link LogCapture#occurrences} appended {@code event.getThrowableProxy()} to the text it
 * searched. {@code ThrowableProxy} has no {@code toString}, so what it appended was
 * {@code ThrowableProxy@1f69937a} — an identity hash, never the exception's message. Every
 * assertion in this package that a fixture does not appear was therefore made against the
 * formatted message alone, while the appender wrote the value in full.
 *
 * <p>That is not a hypothetical door. A failed database write puts the offending value into the
 * driver's exception message, and the same exception is then logged as an attachment by more than
 * one category. So the fixtures below live only inside a throwable: if the scan ever stops
 * following the chain, none of these can pass by accident.
 *
 * <p>This test raises no logger level of its own — {@code LogCapture} raises the root to TRACE for
 * the duration of the capture — but it is extended for isolation anyway, because the capture is a
 * change to the JVM-wide logger context whichever code makes it.
 */
@ExtendWith(LoggerLevelIsolation.class)
class LogCaptureThrowableTest {

  private static final Logger LOG = LoggerFactory.getLogger(LogCaptureThrowableTest.class);

  /** Synthetic, and it opens nothing. Non-hex, so it cannot be mistaken for an identifier. */
  private static final String FIXTURE = "vc_throwable_log_secret_92zqxw";

  @Test
  @DisplayName("A value that exists only in the attached throwable's message is found")
  void findsAValueInTheThrowableMessage() {
    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> LOG.error("the write failed", new IllegalStateException("token=" + FIXTURE)));

    // The message the logger was given carries nothing, so a hit can only have come from the
    // throwable. This is the assertion the old helper failed.
    assertThat(LogCapture.occurrences(events, FIXTURE)).hasSize(1);
  }

  @Test
  @DisplayName("A value in a wrapped cause is found through the chain")
  void findsAValueInTheCauseChain() {
    Throwable root = new IllegalArgumentException("value=" + FIXTURE);
    Throwable middle = new IllegalStateException("could not persist", root);
    Throwable outer = new RuntimeException("transaction rolled back", middle);

    List<ILoggingEvent> events = LogCapture.capturing(() -> LOG.error("rollback", outer));

    // Wrapping is how the value actually travels: the driver builds the message, JPA wraps it,
    // Spring wraps that. Only the innermost link has the value in it.
    assertThat(LogCapture.occurrences(events, FIXTURE)).hasSize(1);
  }

  @Test
  @DisplayName("A value in a suppressed exception is found too")
  void findsAValueInASuppressedException() {
    Throwable outer = new RuntimeException("closing the connection failed");
    outer.addSuppressed(new IllegalStateException("statement=" + FIXTURE));

    List<ILoggingEvent> events = LogCapture.capturing(() -> LOG.error("cleanup", outer));

    // try-with-resources during a rollback puts the interesting failure in the suppressed list,
    // where a cause-only walk would never look.
    assertThat(LogCapture.occurrences(events, FIXTURE)).hasSize(1);
  }

  @Test
  @DisplayName("An event with no throwable and no fixture is still not a hit")
  void doesNotInventHits() {
    List<ILoggingEvent> events = LogCapture.capturing(() -> LOG.error("nothing to see"));

    // The other direction: a scanner that concatenated something unconditionally could match on
    // its own filler rather than on the log line.
    assertThat(LogCapture.occurrences(events, FIXTURE)).isEmpty();
    assertThat(events).isNotEmpty();
  }

  @Test
  @DisplayName("The rendered line names the exception type, so a failure says where it leaked")
  void reportsTheExceptionType() {
    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> LOG.error("the write failed", new IllegalStateException("token=" + FIXTURE)));

    assertThat(LogCapture.occurrences(events, FIXTURE))
        .singleElement()
        .asString()
        .contains(IllegalStateException.class.getName())
        .contains(LogCaptureThrowableTest.class.getName());
  }
}
