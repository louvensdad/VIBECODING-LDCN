package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.vibecode.brain.application.BrainService;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.domain.EvidenceType;
import com.vibecode.project.application.ProjectService;
import com.vibecode.provider.application.ProviderAccountService;
import com.vibecode.provider.domain.AuthenticationType;
import com.vibecode.provider.domain.ProviderAccount;
import com.vibecode.provider.domain.ProviderId;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.support.TestIdentity;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import com.vibecode.vault.domain.SecretMaterial;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The ORM must not print what a user typed.
 *
 * <p>Every module in this codebase can be careful with its own log calls and still leak, because
 * Hibernate writes parameter values and entity field dumps from below the application. Before the
 * levels in application.yml existed, persisting a task through the ordinary service call and
 * capturing the logs produced the objective verbatim on three separate categories. These tests
 * follow the paths where user-written text becomes a row and assert the count is zero.
 */
@SpringBootTest
class HibernateValueLoggingTest {

  /**
   * Exclusive to this task, and synthetic. It is shaped like the credential a developer would
   * paste into a task objective without thinking about it, which is the case that matters — a real
   * secret is never needed to prove a logger printed a field.
   */
  private static final String FIXTURE = "vc_hibernate_log_secret_847291";

  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TaskService tasks;
  @Autowired BrainService brain;
  @Autowired EvidenceService evidence;
  @Autowired ProviderAccountService accounts;
  @Autowired TestIdentity identity;

  @BeforeEach
  void authenticate() {
    identity.createAndAuthenticate("log-owner");
  }

  @AfterEach
  void signOut() {
    identity.clear();
  }

  private record Fixture(UUID projectId, UUID phaseId) {}

  private Fixture project(String name) {
    UUID projectId = projects.create(name, "", "Idea for " + name).getId();
    roadmaps.createOrGet(projectId);
    return new Fixture(projectId, roadmaps.addPhase(projectId, 1, "Phase", null).getId());
  }

  @Test
  @DisplayName("A task objective containing a credential is never printed by the ORM")
  void taskObjectiveIsNotLogged() {
    Fixture fixture = project("Task logging");

    List<ILoggingEvent> events =
        LogCapture.capturing(
            () ->
                tasks.addTask(
                    fixture.projectId(),
                    fixture.phaseId(),
                    1,
                    "Wire up the provider",
                    "OPENAI_API_KEY=" + FIXTURE,
                    RiskLevel.LOW));

    assertClean(events);
  }

  @Test
  @DisplayName("A Brain entry containing a credential is never printed by the ORM")
  void brainEntryIsNotLogged() {
    Fixture fixture = project("Brain logging");

    List<ILoggingEvent> events =
        LogCapture.capturing(
            () ->
                brain.add(
                    fixture.projectId(),
                    BrainEntryType.NOTE,
                    "Deploy notes",
                    "The staging key is " + FIXTURE,
                    "user"));

    assertClean(events);
  }

  @Test
  @DisplayName("Evidence content is never printed by the ORM")
  void evidenceIsNotLogged() {
    Fixture fixture = project("Evidence logging");
    Task task =
        tasks.addTask(fixture.projectId(), fixture.phaseId(), 1, "Run it", "Obj", RiskLevel.LOW);
    tasks.start(fixture.projectId(), task.getId());

    // Bare, with no KEY= in front of it. SensitiveDataRedactor would rewrite an
    // "API_KEY=..." pair before the row is written, and a fixture that never reaches the entity
    // would prove nothing about what the ORM does with the entity. This one is stored verbatim.
    List<ILoggingEvent> events =
        LogCapture.capturing(
            () ->
                evidence.record(
                    fixture.projectId(),
                    task.getId(),
                    EvidenceType.TERMINAL_OUTPUT,
                    "build succeeded, token " + FIXTURE,
                    "terminal"));

    assertClean(events);
  }

  @Test
  @DisplayName("Storing a provider credential logs neither the plaintext nor the ciphertext row")
  void credentialBoundaryIsNotLogged() {
    ProviderAccount account =
        accounts.create(ProviderId.ANTHROPIC, "Claude key", AuthenticationType.API_KEY);

    // The vault stores ciphertext, so the plaintext is not what the ORM would flush here. The
    // point of the case is the path, not the value: the same bind-parameter and entity-dump
    // machinery runs, and it must stay silent on the one path where a leak would be worst.
    List<ILoggingEvent> events =
        LogCapture.capturing(
            () ->
                accounts.storeCredential(
                    account.getId(),
                    SecretMaterial.of(FIXTURE.getBytes(StandardCharsets.UTF_8))));

    assertClean(events);
  }

  private void assertClean(List<ILoggingEvent> events) {
    // "Not empty" would be satisfied by a Spring transaction line, which proves nothing about the
    // ORM. Requiring a statement from org.hibernate.SQL is what makes the assertion below mean
    // what it says: a flush really happened inside the captured window, so the categories that
    // would have printed its values had their chance and stayed quiet.
    assertThat(events)
        .as("no ORM statement was logged, so this capture proves nothing about the ORM")
        .anyMatch(event -> event.getLoggerName().equals("org.hibernate.SQL"));
    assertThat(LogCapture.occurrences(events, FIXTURE)).isEmpty();
    // Not a fragment either — a truncated dump is still a disclosure.
    assertThat(LogCapture.occurrences(events, "847291")).isEmpty();
  }
}
