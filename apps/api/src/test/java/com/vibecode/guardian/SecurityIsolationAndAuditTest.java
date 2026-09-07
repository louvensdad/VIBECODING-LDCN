package com.vibecode.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.domain.User;
import com.vibecode.project.domain.Project;
import com.vibecode.project.infrastructure.ProjectRepository;
import com.vibecode.support.TestIdentity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Isolation across the security surface, and the properties the audit trail is supposed to have.
 *
 * <p>Bob knows every id. Every security and audit route must answer as if Alice's project did not
 * exist — the same answer an unknown id gets, so probing tells him nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIsolationAndAuditTest {

  /**
   * A recognised token shape, so the finding is CRITICAL: a generic {@code SECRET=} assignment is
   * only HIGH, and HIGH does not block a prompt — it warns. The blocked path needs a critical one.
   */
  private static final String SECRET = "sk-vibecodeisolationsecret552310";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ProjectRepository projects;
  @Autowired TestIdentity identity;

  private User alice;
  private User bob;
  private Project aliceProject;
  private String findingId;

  @BeforeEach
  void setUp() throws Exception {
    alice = identity.createUser("alice-iso");
    bob = identity.createUser("bob-iso");
    aliceProject =
        projects.save(new Project(alice.getId(), "Alice Secure", "Privado", "Ideia da Alice"));

    String response =
        mvc.perform(
                post("/api/projects/" + aliceProject.getId() + "/security/inspect")
                    .with(TestIdentity.as(alice))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"sourceType":"GENERIC_TEXT","sourceId":"iso",
                         "content":"OPENAI_API_KEY=%s"}
                        """
                            .formatted(SECRET)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    findingId = json.readTree(response).get(0).get("id").asText();
  }

  private String base() {
    return "/api/projects/" + aliceProject.getId();
  }

  @Test
  @DisplayName("Bob is refused on every security and audit route, and learns nothing")
  void bobIsRefusedEverywhere() throws Exception {
    List<MockHttpServletRequestBuilder> attempts =
        List.of(
            get(base() + "/security"),
            get(base() + "/security/findings"),
            get(base() + "/security/findings/" + findingId),
            get(base() + "/audit"),
            post(base() + "/security/inspect")
                .content("{\"sourceType\":\"GENERIC_TEXT\",\"content\":\"x\"}"),
            post(base() + "/security/findings/" + findingId + "/acknowledge"),
            post(base() + "/security/findings/" + findingId + "/resolve").content("{}"),
            post(base() + "/security/findings/" + findingId + "/accept-risk").content("{}"),
            post(base() + "/security/findings/" + findingId + "/false-positive").content("{}"));

    assertThat(attempts).hasSize(9);

    for (MockHttpServletRequestBuilder attempt : attempts) {
      String body =
          mvc.perform(
                  attempt
                      .with(TestIdentity.as(bob))
                      .with(csrf())
                      .contentType(MediaType.APPLICATION_JSON))
              .andExpect(status().isNotFound())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body)
          .doesNotContain(SECRET)
          .doesNotContain("Alice Secure")
          .doesNotContain(findingId);
    }
  }

  @Test
  @DisplayName("a denied cross-user access is recorded for the owner, not for the intruder")
  void crossUserDenialIsAudited() throws Exception {
    mvc.perform(get(base() + "/security").with(TestIdentity.as(bob)))
        .andExpect(status().isNotFound());

    JsonNode events =
        json.readTree(
            mvc.perform(get(base() + "/audit").with(TestIdentity.as(alice)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    boolean recorded = false;
    for (JsonNode event : events) {
      if ("CROSS_USER_ACCESS_DENIED".equals(event.get("eventType").asText())) {
        recorded = true;
      }
    }
    assertThat(recorded).as("a tentativa negada deveria aparecer na auditoria da Alice").isTrue();

    // Bob still cannot read that trail.
    mvc.perform(get(base() + "/audit").with(TestIdentity.as(bob)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("the audit trail is append-only: no route changes or removes an event")
  void auditIsAppendOnly() throws Exception {
    String before =
        mvc.perform(get(base() + "/audit").with(TestIdentity.as(alice)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    int countBefore = json.readTree(before).size();
    assertThat(countBefore).isPositive();

    // There is no verb for changing an event. Anything but GET must fail.
    for (MockHttpServletRequestBuilder mutation :
        List.of(delete(base() + "/audit"), put(base() + "/audit"), patch(base() + "/audit"))) {
      mvc.perform(mutation.with(TestIdentity.as(alice)).with(csrf()))
          .andExpect(status().is4xxClientError());
    }

    // Further activity only appends.
    mvc.perform(
        post(base() + "/security/findings/" + findingId + "/acknowledge")
            .with(TestIdentity.as(alice))
            .with(csrf()));

    JsonNode after =
        json.readTree(
            mvc.perform(get(base() + "/audit").with(TestIdentity.as(alice)))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(after.size()).isGreaterThan(countBefore);
  }

  @Test
  @DisplayName("a blocked prompt is audited, even though prompt generation is a read-only path")
  void blockedPromptIsAudited() throws Exception {
    // Generating a prompt runs in a read-only transaction; an audit write that joined it would be
    // discarded without error, and the event would simply never appear.
    mvc.perform(
            post(base() + "/prompts/generate")
                .with(TestIdentity.as(alice))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    JsonNode events =
        json.readTree(
            mvc.perform(get(base() + "/audit").with(TestIdentity.as(alice)))
                .andReturn()
                .getResponse()
                .getContentAsString());

    boolean recorded = false;
    for (JsonNode event : events) {
      if ("PROMPT_BLOCKED".equals(event.get("eventType").asText())) {
        recorded = true;
      }
    }
    assertThat(recorded).as("o bloqueio do prompt deveria estar na auditoria").isTrue();
  }

  @Test
  @DisplayName("no audit event carries the secret that caused it")
  void auditNeverCarriesTheSecret() throws Exception {
    String body =
        mvc.perform(get(base() + "/audit").with(TestIdentity.as(alice)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).isNotBlank().doesNotContain(SECRET);
    // Nor any of the credential material an audit trail must never absorb.
    assertThat(body.toLowerCase())
        .doesNotContain("jsessionid")
        .doesNotContain("xsrf-token")
        .doesNotContain("password=");
  }
}
