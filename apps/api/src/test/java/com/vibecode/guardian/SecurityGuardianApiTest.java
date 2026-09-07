package com.vibecode.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.domain.User;
import com.vibecode.project.domain.Project;
import com.vibecode.project.infrastructure.ProjectRepository;
import com.vibecode.support.TestIdentity;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs without a wrapping transaction, so each request commits the way a real one does.
 *
 * <p>Audit events are written in their own transaction on purpose; inside a rollback-only test
 * transaction they would not see the project row and would fail on its foreign key.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityGuardianApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ProjectRepository projectRepository;
  @Autowired TestIdentity testIdentity;

  private User alice;
  private User bob;
  private Project aliceProject;

  @BeforeEach
  void setUp() {
    alice = testIdentity.createUser("alice");
    bob = testIdentity.createUser("bob");
    aliceProject =
        projectRepository.save(
            new Project(alice.getId(), "Alice App", "Secure", "Idea"));
  }

  @Test
  @DisplayName("Inspect creates finding and calculates assessment")
  void inspectAndAssess() throws Exception {
    String payload =
        """
        {"sourceType":"GENERIC_TEXT","sourceId":"test-1","content":"DATABASE_URL=postgres://user:pass123@host:5432/db"}
        """;

    mvc.perform(
            post("/api/projects/" + aliceProject.getId() + "/security/inspect")
                .with(TestIdentity.as(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].severity").value("HIGH"))
        .andExpect(jsonPath("$[0].evidence").value("postgres://user:[REDACTED]@host:5432/db"));

    mvc.perform(
            get("/api/projects/" + aliceProject.getId() + "/security")
                .with(TestIdentity.as(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.score").value(85))
        .andExpect(jsonPath("$.high").value(1))
        .andExpect(jsonPath("$.gateStatus").value("REQUIRES_APPROVAL"));
  }

  @Test
  @DisplayName("Deduplication: repeated inspections increment occurrenceCount instead of creating duplicate findings")
  void deduplication() throws Exception {
    String payload =
        """
        {"sourceType":"GENERIC_TEXT","sourceId":"test-1","content":"API_KEY=myrealsecrettoken9999"}
        """;

    for (int i = 0; i < 3; i++) {
      mvc.perform(
              post("/api/projects/" + aliceProject.getId() + "/security/inspect")
                  .with(TestIdentity.as(alice)).with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(payload))
          .andExpect(status().isOk());
    }

    JsonNode findings =
        json.readTree(
            mvc.perform(
                    get("/api/projects/" + aliceProject.getId() + "/security/findings")
                        .with(TestIdentity.as(alice)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).get("occurrenceCount").asInt()).isEqualTo(3);
  }

  @Test
  @DisplayName("Finding lifecycle: acknowledge, accept-risk, and resolve")
  void findingLifecycle() throws Exception {
    String payload =
        """
        {"sourceType":"GENERIC_TEXT","sourceId":"test-1","content":"API_KEY=myrealsecrettoken9999"}
        """;

    String response =
        mvc.perform(
                post("/api/projects/" + aliceProject.getId() + "/security/inspect")
                    .with(TestIdentity.as(alice)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID findingId = UUID.fromString(json.readTree(response).get(0).get("id").asText());

    // Acknowledge
    mvc.perform(
            post("/api/projects/" + aliceProject.getId() + "/security/findings/" + findingId + "/acknowledge")
                .with(TestIdentity.as(alice)).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

    // Resolve
    mvc.perform(
            post("/api/projects/" + aliceProject.getId() + "/security/findings/" + findingId + "/resolve")
                .with(TestIdentity.as(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Substituído por vault\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESOLVED"))
        .andExpect(jsonPath("$.resolutionReason").value("Substituído por vault"));

    // Check assessment returns to 100
    mvc.perform(
            get("/api/projects/" + aliceProject.getId() + "/security")
                .with(TestIdentity.as(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.score").value(100))
        .andExpect(jsonPath("$.gateStatus").value("PASS"));
  }

  @Test
  @DisplayName("IDOR: Bob cannot read or mutate Alice's security findings or audit")
  void idorProtection() throws Exception {
    String payload =
        """
        {"sourceType":"GENERIC_TEXT","sourceId":"test-1","content":"API_KEY=myrealsecrettoken9999"}
        """;

    String response =
        mvc.perform(
                post("/api/projects/" + aliceProject.getId() + "/security/inspect")
                    .with(TestIdentity.as(alice)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID findingId = UUID.fromString(json.readTree(response).get(0).get("id").asText());

    // Bob tries to GET assessment
    mvc.perform(
            get("/api/projects/" + aliceProject.getId() + "/security")
                .with(TestIdentity.as(bob)))
        .andExpect(status().isNotFound());

    // Bob tries to GET findings
    mvc.perform(
            get("/api/projects/" + aliceProject.getId() + "/security/findings")
                .with(TestIdentity.as(bob)))
        .andExpect(status().isNotFound());

    // Bob tries to ACK finding
    mvc.perform(
            post("/api/projects/" + aliceProject.getId() + "/security/findings/" + findingId + "/acknowledge")
                .with(TestIdentity.as(bob)).with(csrf()))
        .andExpect(status().isNotFound());

    // Bob tries to GET audit
    mvc.perform(
            get("/api/projects/" + aliceProject.getId() + "/audit")
                .with(TestIdentity.as(bob)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Audit trail records finding creation and lifecycle events")
  void auditTrail() throws Exception {
    String payload =
        """
        {"sourceType":"GENERIC_TEXT","sourceId":"test-1","content":"API_KEY=myrealsecrettoken9999"}
        """;

    mvc.perform(
            post("/api/projects/" + aliceProject.getId() + "/security/inspect")
                .with(TestIdentity.as(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isOk());

    JsonNode auditEvents =
        json.readTree(
            mvc.perform(
                    get("/api/projects/" + aliceProject.getId() + "/audit")
                        .with(TestIdentity.as(alice)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(auditEvents.size()).isGreaterThanOrEqualTo(1);
    assertThat(auditEvents.get(0).get("eventType").asText()).isEqualTo("SECURITY_FINDING_CREATED");
  }
}

