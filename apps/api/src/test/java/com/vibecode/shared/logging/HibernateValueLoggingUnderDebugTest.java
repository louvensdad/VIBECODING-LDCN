package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.vibecode.project.application.ProjectService;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.support.TestIdentity;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.RiskLevel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The same guarantee under the settings an operator reaches for when something is wrong.
 *
 * <p>Turning debug logging on is not an unusual act; it is the first thing anyone does with a
 * misbehaving query. So the interesting question is not whether the values stay out of an INFO log
 * — they always did — but whether they stay out when someone raises the level on the root logger,
 * on all of Hibernate, and on a profile that does not exist yet. They do, because each leaking
 * category carries a level of its own and an inherited level cannot override one that is set.
 *
 * <p>What this does not claim: naming a leaking category directly, as
 * {@code logging.level.org.hibernate.orm.jdbc.bind=TRACE}, does re-enable it. That is the
 * documented escape hatch and it is meant to work — the fix makes value logging a deliberate
 * choice about one category, not a side effect of debugging.
 */
@SpringBootTest
@ActiveProfiles("prod")
@TestPropertySource(
    properties = {
      "logging.level.root=DEBUG",
      "logging.level.org.hibernate=DEBUG",
      "logging.level.org.hibernate.orm=DEBUG",
      "logging.level.org.hibernate.orm.jdbc=DEBUG",
      "logging.level.org.hibernate.SQL=DEBUG",
      "spring.jpa.show-sql=true",
      // The prod profile makes the vault refuse the local key provider, which is correct and not
      // what this test is about. Accepting it here keeps the profile real: everything else about
      // prod still applies, including the logging levels under test.
      "vibecode.vault.allow-local-key-provider-outside-development=true"
    })
class HibernateValueLoggingUnderDebugTest {

  private static final String FIXTURE = "vc_hibernate_log_secret_847291";

  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TaskService tasks;
  @Autowired TestIdentity identity;

  @BeforeEach
  void authenticate() {
    identity.createAndAuthenticate("debug-log-owner");
  }

  @AfterEach
  void signOut() {
    identity.clear();
  }

  @Test
  @DisplayName("Debug logging everywhere, and an unknown profile, still print no field values")
  void debugEverywhereStillPrintsNoValues() {
    UUID projectId = projects.create("Debug logging", "", "Idea").getId();
    roadmaps.createOrGet(projectId);
    UUID phaseId = roadmaps.addPhase(projectId, 1, "Phase", null).getId();

    List<ILoggingEvent> events =
        LogCapture.capturing(
            () ->
                tasks.addTask(
                    projectId, phaseId, 1, "Rotate the key", "OPENAI_API_KEY=" + FIXTURE,
                    RiskLevel.LOW));

    assertThat(events).isNotEmpty();
    assertThat(LogCapture.occurrences(events, FIXTURE)).isEmpty();
    assertThat(LogCapture.occurrences(events, "847291")).isEmpty();
  }

  @Test
  @DisplayName("Structural SQL survives: the fix silences values, not the query log")
  void structuralSqlIsStillAvailable() {
    UUID projectId = projects.create("SQL still logs", "", "Idea").getId();
    roadmaps.createOrGet(projectId);
    UUID phaseId = roadmaps.addPhase(projectId, 1, "Phase", null).getId();

    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> tasks.addTask(projectId, phaseId, 1, "T", "Objective", RiskLevel.LOW));

    // Switching Hibernate logging off wholesale would have been the easy fix and the wrong one.
    // The statement, with its placeholders, is what makes a slow or wrong query diagnosable.
    assertThat(events)
        .anySatisfy(
            event -> {
              assertThat(event.getLoggerName()).isEqualTo("org.hibernate.SQL");
              assertThat(event.getFormattedMessage()).contains("insert into tasks");
              assertThat(event.getFormattedMessage()).contains("?");
            });
  }
}
