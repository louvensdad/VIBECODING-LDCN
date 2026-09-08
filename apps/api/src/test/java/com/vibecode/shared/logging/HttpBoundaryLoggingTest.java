package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.vibecode.identity.domain.User;
import com.vibecode.project.application.ProjectService;
import com.vibecode.provider.web.ProviderAccountDtos.StoreCredentialRequest;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.support.TestIdentity;
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

/**
 * The same defect, one layer above the ORM.
 *
 * <p>By the time Hibernate flushes a task, Spring MVC has already deserialized the request body
 * into a DTO and printed that DTO — full at TRACE, truncated at DEBUG. A probe that POSTed the
 * fixture and grouped every captured event by logger found four categories carrying it: the two
 * message-converter processors, HandlerMethod's argument list, and Hibernate Validator's
 * traversable resolver. The first three are pinned; the fourth is a different layer and is
 * recorded in application.yml rather than folded in here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpBoundaryLoggingTest {

  private static final String FIXTURE = "vc_hibernate_log_secret_847291";

  @Autowired MockMvc mvc;
  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TestIdentity identity;

  private User owner;

  @BeforeEach
  void authenticate() {
    owner = identity.createAndAuthenticate("http-log-owner");
  }

  @AfterEach
  void signOut() {
    identity.clear();
  }

  @Test
  @DisplayName("A task objective posted over HTTP is not printed by the web layer either")
  void requestBodyIsNotLogged() throws Exception {
    UUID projectId = projects.create("HTTP logging", "", "Idea").getId();
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
                                "{\"position\":1,\"title\":\"T\",\"objective\":\"OPENAI_API_KEY="
                                    + FIXTURE
                                    + "\",\"riskLevel\":\"LOW\"}"))
                    .andReturn();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    // A request really was handled inside the window: without this the assertion below could pass
    // on an empty or unrelated capture.
    assertThat(events)
        .as("no request was dispatched, so this capture proves nothing about the web layer")
        .anyMatch(event -> event.getLoggerName().startsWith("org.springframework.web"));

    // No category at all, and the failure names whichever one broke that rather than only saying
    // something leaked. Everything on the ordinary path of a validated request body is pinned:
    // the two message-converter processors, the resolved argument list, and Bean Validation's
    // traversable resolver, which prints the object it is walking on every validated DTO.
    assertThat(leakingLoggers(events)).isEmpty();
  }

  /** The distinct logger names that printed the fixture, sorted, for an exact-set assertion. */
  private List<String> leakingLoggers(List<ILoggingEvent> events) {
    return events.stream()
        .filter(event -> String.valueOf(event.getFormattedMessage()).contains("847291"))
        .map(ILoggingEvent::getLoggerName)
        .distinct()
        .sorted()
        .toList();
  }

  @Test
  @DisplayName("A provider credential posted over HTTP is not printed by the web layer")
  void credentialRequestBodyIsNotLogged() throws Exception {
    String created =
        mvc.perform(
                post("/api/provider-accounts")
                    .with(TestIdentity.as(owner))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"provider\":\"ANTHROPIC\",\"displayName\":\"L\","
                            + "\"authenticationType\":\"API_KEY\"}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID accountId =
        UUID.fromString(created.replaceAll(".*\"id\"\s*:\s*\"([^\"]+)\".*", "$1"));

    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> {
              try {
                mvc.perform(
                        put("/api/provider-accounts/{id}/credential", accountId)
                            .with(TestIdentity.as(owner))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"credential\":\"" + FIXTURE + "\"}"))
                    .andReturn();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    assertThat(events)
        .as("no request was dispatched, so this capture proves nothing about the web layer")
        .anyMatch(event -> event.getLoggerName().startsWith("org.springframework.web"));
    assertThat(LogCapture.occurrences(events, FIXTURE)).isEmpty();
  }

  @Test
  @DisplayName("StoreCredentialRequest prints nothing of the credential when something prints it")
  void credentialRequestToStringIsRedacted() {
    // The pins above stop the web layer printing any DTO. This checks the other half, which is
    // what protected the credential before those pins existed: the request type's own toString.
    // Verified end to end before the pin went in — at TRACE the processor logged
    // `Read "application/json;charset=UTF-8" to [StoreCredentialRequest[credential=redacted]]`
    // and HandlerMethod logged `Arguments: [<id>, StoreCredentialRequest[credential=redacted]]`,
    // with zero occurrences of the credential. Asserting the type directly keeps that property
    // guarded now that the categories are silent and an end-to-end check could no longer see it.
    StoreCredentialRequest request = new StoreCredentialRequest(FIXTURE.toCharArray());

    assertThat(request.toString()).doesNotContain(FIXTURE).doesNotContain("847291");
    assertThat(request.toString()).isEqualTo("StoreCredentialRequest[credential=redacted]");
  }
}
