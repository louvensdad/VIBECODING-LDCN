package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * This application writes to the console and nowhere else. It must stay that way.
 *
 * <p>Adding logback-spring.xml for the TurboFilter came within one line of undoing the change it was
 * made for. Including {@code org/springframework/boot/logging/logback/base.xml} looks like asking
 * for Spring Boot's defaults and is not: base.xml is a Boot 1.1 compatibility shim that defines
 * {@code LOG_FILE} with a fallback to {@code ${java.io.tmpdir}/spring.log} and attaches the FILE
 * appender to the root <em>unconditionally</em>, where Boot's own default attaches it only when
 * {@code logging.file.name} or {@code logging.file.path} is set. This application sets neither, and
 * the first full build after that include produced a nine-megabyte rolling log file with a week of
 * retention, in a directory shared with every other user of the machine.
 *
 * <p>The size was not the problem. The nine pinned categories in application.yml exist to control
 * what reaches a log file, and everything they tolerate at INFO — the email addresses
 * SecurityEventLogger records among them — had been console-only and was now durable. A change made
 * to narrow what reaches a log file must not begin by creating one.
 *
 * <p>Two assertions, because either alone can pass while the other fails: the running context has no
 * file appender, and the shipped configuration does not ask for one.
 */
@SpringBootTest
class LogFileAppenderIsNotAttachedTest {

  @Test
  @DisplayName("No appender in the running logger context writes to a file")
  void noFileAppenderIsAttached() {
    List<String> fileAppenders = new ArrayList<>();
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    for (Logger logger : context.getLoggerList()) {
      for (Iterator<Appender<?>> it = appendersOf(logger); it.hasNext(); ) {
        Appender<?> appender = it.next();
        if (appender instanceof FileAppender<?> file) {
          fileAppenders.add(logger.getName() + " -> " + appender.getName() + " @" + file.getFile());
        }
      }
    }
    assertThat(fileAppenders)
        .as("this application logs to the console; a file appender puts tolerated data on disk")
        .isEmpty();
  }

  @Test
  @DisplayName("The shipped logback-spring.xml does not include base.xml or a FILE appender")
  void shippedConfigurationAsksForNoFile() throws IOException {
    String configuration = shippedLogbackConfiguration();

    // Named rather than matched loosely, because base.xml is the specific trap: it is the include
    // that reads as "the defaults" and is not.
    assertThat(configuration)
        .as("base.xml attaches the FILE appender unconditionally; include defaults.xml instead")
        .doesNotContain("logback/base.xml");
    assertThat(configuration)
        .as("no appender-ref to FILE, and no file-appender.xml include")
        .doesNotContain("file-appender.xml")
        .doesNotContain("ref=\"FILE\"");
    // The positive half. Without these the console output would be lost entirely, which is the
    // other way to get this wrong.
    assertThat(configuration)
        .as("the console appender and the conversion defaults must still be included")
        .contains("logback/defaults.xml")
        .contains("logback/console-appender.xml")
        .contains("ref=\"CONSOLE\"");
  }

  @SuppressWarnings("unchecked")
  private static Iterator<Appender<?>> appendersOf(Logger logger) {
    return (Iterator<Appender<?>>) (Iterator<?>) logger.iteratorForAppenders();
  }

  /** Read from the build output rather than the source tree, so it is the file that ships. */
  private String shippedLogbackConfiguration() throws IOException {
    URL url = getClass().getClassLoader().getResource("logback-spring.xml");
    assertThat(url).as("logback-spring.xml must be on the main classpath").isNotNull();
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
