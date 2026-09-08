package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.vibecode.identity.domain.User;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The same guarantee under the settings an operator reaches for when something is wrong.
 *
 * <p>Turning debug logging on is not an unusual act; it is the first thing anyone does with a
 * misbehaving query. So the interesting question is not whether the values stay out of an INFO log
 * — they always did — but whether they stay out when someone raises the level on the parents of
 * the pinned categories, and on a profile that does not exist yet. They do, because each leaking
 * category carries a level of its own and an inherited level cannot override one that is set.
 *
 * <p>The parent levels below are not uniform, and that is deliberate. Some categories only emit at
 * TRACE — JdbcBindingLogging guards its calls with {@code isTraceEnabled()} — so setting their
 * parents to DEBUG would assert nothing: DEBUG could not have re-enabled them even with no pins at
 * all. Each parent is therefore raised to the level that would actually reopen its child.
 *
 * <p>All nine pins are exercised, not just the ORM's four. The work goes through MockMvc rather
 * than straight to the service, because four of the nine only ever run on a real request, and one
 * of those only on a request that fails validation. A test that calls the service directly cannot
 * observe them however high it sets the levels.
 *
 * <p>What this does not claim: naming a leaking category directly, as
 * {@code logging.level.org.hibernate.orm.jdbc.bind=TRACE}, does re-enable it. That is the
 * documented escape hatch and it is meant to work — the fix makes value logging a deliberate
 * choice about one category, not a side effect of debugging.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@TestPropertySource(
    properties = {
      "logging.level.root=DEBUG",
      // The direct parent of jdbc.bind and jdbc.extract, at the only level that could reopen them.
      "logging.level.org.hibernate.orm.jdbc=TRACE",
      // The direct parent of ResourceRegistryStandardImpl, likewise.
      "logging.level.org.hibernate.resource.jdbc=TRACE",
      // EntityPrinter's parent. It logs at DEBUG, so DEBUG is the level that matters here.
      "logging.level.org.hibernate.internal.util=DEBUG",
      "logging.level.org.hibernate=DEBUG",
      "logging.level.org.hibernate.orm=DEBUG",
      "logging.level.org.hibernate.SQL=DEBUG",
      // The web-layer parents, at the levels that would reopen their children. The message
      // converters, HandlerMethod and the exception resolver all live under org.springframework
      // .web; the validator under org.hibernate.validator. Without these the class name would be
      // claiming coverage of nine pins while exercising four.
      "logging.level.org.springframework.web=TRACE",
      "logging.level.org.springframework.web.servlet.mvc.method.annotation=TRACE",
      "logging.level.org.springframework.web.method=TRACE",
      "logging.level.org.hibernate.validator=TRACE",
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
  @Autowired MockMvc mvc;

  @BeforeEach
  void authenticate() {
    identity.createAndAuthenticate("debug-log-owner");
  }

  @AfterEach
  void signOut() {
    identity.clear();
  }

  @Test
  @DisplayName("Every parent raised to the level that would reopen its child still prints no values")
  void debugEverywhereStillPrintsNoValues() throws Exception {
    User owner = identity.createAndAuthenticate("debug-http-owner");
    UUID projectId = projects.create("Debug logging", "", "Idea").getId();
    roadmaps.createOrGet(projectId);
    UUID phaseId = roadmaps.addPhase(projectId, 1, "Phase", null).getId();

    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> {
              try {
                mvc.perform(
                        post("/api/projects/{p}/roadmap/phases/{ph}/tasks", projectId, phaseId)
                            .with(TestIdentity.as(owner))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                "{\"position\":1,\"title\":\"Rotate the key\",\"objective\":"
                                    + "\"OPENAI_API_KEY="
                                    + FIXTURE
                                    + "\",\"riskLevel\":\"LOW\"}"))
                    .andExpect(status().isCreated());
                // The rejected path in the same window: it is the one that reaches the exception
                // resolver, and it was the gap that made the ninth pin necessary.
                mvc.perform(
                        post("/api/auth/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                "{\"email\":\"debug-rejected@example.com\",\"password\":\""
                                    + FIXTURE
                                    + "z".repeat(300)
                                    + "\",\"displayName\":\"D\"}"))
                    .andExpect(status().isBadRequest());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

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
