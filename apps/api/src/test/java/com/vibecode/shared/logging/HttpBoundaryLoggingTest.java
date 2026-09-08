package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                    // Without this the test passes on a 404: the anti-vacuity check below is
                    // satisfied by DispatcherServlet alone, and a request that never reaches a
                    // controller never deserializes a body for anything to print.
                    .andExpect(status().isCreated());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    // The status assertion above is what makes this window meaningful; this only adds that the
    // web layer was logging at all. On its own it proves very little: DispatcherServlet and the
    // handler mappings log on any dispatch, including a 404 that never deserializes a body — this
    // whole test was green against a non-existent route until the status check went in.
    assertThat(events)
        .as("the web layer logged nothing at all, which should be impossible for a real dispatch")
        .anyMatch(event -> event.getLoggerName().startsWith("org.springframework.web"));

    // No category at all, and the failure names whichever one broke that rather than only saying
    // something leaked. Everything on the ordinary path of a validated request body is pinned:
    // the two message-converter processors, the resolved argument list, and Bean Validation's
    // traversable resolver, which prints the object it is walking on every validated DTO.
    assertThat(leakingLoggers(events)).isEmpty();
  }

  /**
   * The distinct logger names that printed the fixture, sorted, so a failure names the category.
   *
   * <p>Delegates to {@link LogCapture#occurrences} rather than reading the formatted message
   * directly, because that helper also inspects the throwable: an exception carrying the value in
   * its message reaches the log file exactly as effectively as a formatted argument does, and the
   * validation path proved that is not hypothetical.
   */
  private List<String> leakingLoggers(List<ILoggingEvent> events) {
    List<String> hits = LogCapture.occurrences(events, "847291");
    return hits.stream().map(hit -> hit.substring(0, hit.indexOf(" @"))).distinct().sorted().toList();
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
                    .andExpect(status().isOk());
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
  @DisplayName("A password rejected by validation is not printed by the exception resolver")
  void rejectedRequestBodyIsNotLogged() throws Exception {
    // Every probe on this task until now sent a request that succeeded, and that is why this was
    // missed: the value in a *rejected* request travels a different route out. Spring builds a
    // FieldError whose toString carries `rejected value [<the whole value>]`, wraps it in a
    // MethodArgumentNotValidException, and the exception resolver prints the exception at DEBUG
    // before ApiExceptionHandler — which is careful, and never echoes the value — runs at all.
    //
    // A password, because it is the worst thing this endpoint can be handed and the field is the
    // only one carrying the fixture: nothing else in the request could account for a hit.
    String tooLong = FIXTURE + "z".repeat(300);

    List<ILoggingEvent> events =
        LogCapture.capturing(
            () -> {
              try {
                mvc.perform(
                        post("/api/auth/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                "{\"email\":\"rejected@example.com\",\"password\":\""
                                    + tooLong
                                    + "\",\"displayName\":\"D\"}"))
                    // The rejection is the point. A 201 here would mean the constraint moved and
                    // the test is no longer exercising the validation-failure path at all.
                    .andExpect(status().isBadRequest());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    assertThat(events)
        .as("no request was dispatched, so this capture proves nothing")
        .anyMatch(event -> event.getLoggerName().startsWith("org.springframework.web"));
    assertThat(leakingLoggers(events)).isEmpty();
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
