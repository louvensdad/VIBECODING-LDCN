package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextPack;
import com.vibecode.context.infrastructure.persistence.ContextPackRepository;
import com.vibecode.shared.logging.LogCapture;
import com.vibecode.support.logging.LoggerLevelIsolation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What this application says out loud while a compilation succeeds, is refused, or fails.
 *
 * <p>Four moments are covered, and they fail differently enough that one of them proving clean says
 * nothing about the others: a compilation that works, one refused because the caller may not read
 * the project, one refused because the argument is invalid, and one that blows up while the
 * database is being written.
 *
 * <p><b>Every line is read through {@link LogCapture#lineOf}, which walks the throwable chain.</b>
 * Formatted message, cause, cause's cause, suppressed — all of it. A leak that travels only in an
 * exception's message is the common case here rather than the exotic one, and a check that reads
 * only {@code getFormattedMessage()} would pass while the secret sits in the stack trace two frames
 * down. Nothing in this file ever stringifies the throwable proxy directly; that idiom is blind and
 * has already shipped once.
 *
 * <p>The root logger is raised to TRACE for the duration, which is why the class carries {@link
 * LoggerLevelIsolation} — an ArchUnit rule requires it, and without it the level would outlive this
 * class and change what every later test in the same JVM can see.
 */
@ExtendWith(LoggerLevelIsolation.class)
class ContextCompilationLogLeakTest extends ContextProbeFixture {

  @Autowired ContextPackRepository packs;

  private Planted planted;
  private Logger root;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void plantThenListen() {
    planted = plantTheProbeEverywhere("log-owner");
    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    captured = new ListAppender<>();
    captured.start();
    root.addAppender(captured);
    root.setLevel(Level.TRACE);
  }

  @AfterEach
  void stopListening() {
    root.detachAppender(captured);
    captured.stop();
  }

  @Test
  @DisplayName("A compilation that succeeds says nothing containing the probe")
  void successLeaksNothing() {
    CompiledContextPack compiled =
        assembler.assemble(planted.projectId(), "CTX-09 log success " + KEYED_PROBE, GENEROUS);
    assertThat(compiled.admittedItems()).isNotEmpty();

    assertThat(hits(PROBE)).isEmpty();
    assertThat(hits(DENIED_MARKER))
        .as("a refused item's content must not reach a log either")
        .isEmpty();
    assertThat(events()).as("the run must have logged something to search").isNotEmpty();
  }

  @Test
  @DisplayName("A compilation refused for another user's project says nothing containing the probe")
  void aRefusedCallerLeaksNothing() {
    identity.clear();
    identity.createAndAuthenticate("log-bob");

    Throwable refusal =
        catchThrowable(() -> assembler.assemble(planted.projectId(), "CTX-09 log bob", GENEROUS));
    assertThat(refusal).isNotNull();

    assertThat(hits(PROBE)).isEmpty();
    assertThat(hits("Probe project"))
        .as("a refusal must not describe the project it refused to open")
        .isEmpty();

    // Non-vacuous: the refusal really was logged, so the emptiness above is about a populated
    // capture rather than about an appender that never received anything.
    assertThat(hits("ACCESS_DENIED"))
        .as("the access gate logs the denial, which is what makes this capture live")
        .isNotEmpty();
  }

  @Test
  @DisplayName("A validation failure says lengths, not the value whose length it is reporting")
  void aValidationFailureLeaksNothing() {
    String padding = "R".repeat(ContextPack.MAX_TASK_REFERENCE_LENGTH - 11);
    String reference = padding + " TOKEN=" + PROBE;

    Throwable refusal =
        catchThrowable(() -> assembler.assemble(planted.projectId(), reference, GENEROUS));
    assertThat(refusal).isInstanceOf(IllegalArgumentException.class);

    // The exception itself must not carry the value, and neither must anything logged about it.
    for (Throwable link = refusal; link != null; link = link.getCause()) {
      assertThat(String.valueOf(link.getMessage()))
          .as("the refusal names a length, never the text it measured")
          .doesNotContain(PROBE);
    }
    assertThat(hits(PROBE)).isEmpty();
  }

  @Test
  @DisplayName("A write that the database rejects says nothing containing the probe")
  void aPersistenceFailureLeaksNothing() {
    Throwable failure = catchThrowable(() -> ContextPersistenceFailure.provoke(packs, planted.projectId()));
    assertThat(failure)
        .as("the provocation must actually fail, or this test proves nothing about failing")
        .isNotNull();

    // The exception in hand is NOT claimed to be clean. A SQLException raised by the driver can
    // carry the offending value in its own message, and asserting otherwise here would be false.
    // What is asserted is the boundary.
    List<String> mentions = hits(ContextPersistenceFailure.OVERSIZED_IDENTIFIER_PROBE);

    assertThat(mentions)
        .as("this application's own loggers say nothing about the value the database refused")
        .allSatisfy(line -> assertThat(line).doesNotStartWith("com.vibecode"));

    assertThat(mentions)
        .as("and the driver's rejection detail is not what any of them is repeating")
        .allSatisfy(
            line ->
                assertThat(line.toLowerCase(java.util.Locale.ROOT))
                    .doesNotContain("too long")
                    .doesNotContain("value too long")
                    .doesNotContain("22001"));

    // What does mention it, and why this is not softened into silence: Hibernate's own persistence
    // context logs `ContextPackItemEntity.toString()` at DEBUG, and that toString carries the
    // item's id. The id is an identifier a collector constructed — here it is 240 characters of
    // deliberate junk — and the entity's toString is written to disclose identity and nothing else.
    // So the assertion is that those lines carry the id and not the item, which is a real property
    // of that toString rather than an exemption for a logger nobody wants to look at.
    assertThat(mentions)
        .as("an entity's toString may say which item it is; it may not say what the item said")
        .allSatisfy(
            line ->
                assertThat(line)
                    .doesNotContain("An item whose identifier is longer")
                    .doesNotContain("entirely unremarkable"));

    assertThat(hits(PROBE)).isEmpty();
  }

  @Test
  @DisplayName("The capture is reading throwables, not only formatted messages")
  void theCaptureItselfSeesIntoAThrowable() {
    // Proves the instrument before trusting its readings. A check built on getFormattedMessage()
    // alone would return nothing here, and would therefore have returned nothing above for a leak
    // that travelled in a cause.
    org.slf4j.Logger probeLogger = LoggerFactory.getLogger("com.vibecode.probe.contextlogleak");
    probeLogger.error(
        "a message with nothing in it",
        new IllegalStateException(
            "outer", new IllegalStateException("inner carrying " + DENIED_MARKER)));

    List<String> found = hits(DENIED_MARKER);
    assertThat(found)
        .as("the marker is two causes deep and in no formatted message; the reader must find it")
        .isNotEmpty();
    assertThat(events())
        .filteredOn(event -> event.getFormattedMessage().contains(DENIED_MARKER))
        .as("and it is genuinely not in any formatted message")
        .isEmpty();
  }

  @Test
  @DisplayName("Nothing logged about a pack repeats what any admitted item said")
  void noLogLineRepeatsAdmittedContent() {
    CompiledContextPack compiled =
        assembler.assemble(planted.projectId(), "CTX-09 log content", GENEROUS);

    List<String> lines = new ArrayList<>();
    for (ILoggingEvent event : events()) {
      lines.add(LogCapture.lineOf(event));
    }
    for (AdmittedContextItem admitted : compiled.admittedItems()) {
      String content = admitted.item().content();
      if (content.length() < 40) {
        // Too short to be distinctive; a coincidental match would say nothing.
        continue;
      }
      assertThat(lines)
          .as("a log line repeating the content of item %s", admitted.id())
          .noneMatch(line -> line.contains(content));
    }
  }

  private List<ILoggingEvent> events() {
    return List.copyOf(captured.list);
  }

  /** Every captured line, message and throwable chain alike, that contains the needle. */
  private List<String> hits(String needle) {
    List<String> found = new ArrayList<>();
    for (ILoggingEvent event : events()) {
      String line = LogCapture.lineOf(event);
      if (line.contains(needle)) {
        found.add(event.getLoggerName() + " @" + event.getLevel() + ": " + line);
      }
    }
    return found;
  }
}
