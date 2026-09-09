package com.vibecode.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The end-to-end acceptance test for Phase 4:
 * Proves that evidence with exposed credentials triggers critical findings, blocks the
 * safety gate, halts advancement at REVIEW_SECURITY, disables prompt copy, and denies all
 * access to Bob with 404s.
 */
@TestPropertySource(properties = "vibecode.test.context=real-csrf-flow-guardian")
@SpringBootTest
@AutoConfigureMockMvc
class SecurityGuardianEndToEndTest {

  private static final String PASSWORD = "uma senha suficientemente longa";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private final class Session {

    final MockHttpSession session = new MockHttpSession();
    final String email;
    Cookie csrfCookie;
    String csrfToken;

    Session(String name) throws Exception {
      this.email = name + "-" + UUID.randomUUID() + "@example.com";
      refreshCsrf();
      send(
              "/api/auth/register",
              """
              {"email":"%s","password":"%s","displayName":"%s"}
              """
                  .formatted(email, PASSWORD, name))
          .andExpect(status().isCreated());
      send("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))
          .andExpect(status().isOk());
    }

    void refreshCsrf() throws Exception {
      MvcResult result = mvc.perform(get("/api/auth/csrf").session(session)).andReturn();
      csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
      csrfToken = json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
      request = request.session(session).header("X-XSRF-TOKEN", csrfToken);
      return csrfCookie == null ? request : request.cookie(csrfCookie);
    }

    ResultActions send(String url, String body) throws Exception {
      MockHttpServletRequestBuilder request =
          authed(post(url)).contentType(MediaType.APPLICATION_JSON);
      return mvc.perform(body == null ? request : request.content(body));
    }

    ResultActions read(String url) throws Exception {
      return mvc.perform(get(url).session(session));
    }

    String idFrom(ResultActions actions) throws Exception {
      JsonNode node = json.readTree(actions.andReturn().getResponse().getContentAsString());
      return node.get("id").asText();
    }
  }

  @Test
  @DisplayName("End-to-End: Evidence with exposed key blocks Gate and NextStep; remediation resolves and Bob gets 404")
  void endToEndGuardianWorkflow() throws Exception {
    Session alice = new Session("alice");

    // 1. Alice creates BarberFlow project, roadmap, phase and task
    String projectId =
        alice.idFrom(
            alice
                .send(
                    "/api/projects",
                    """
                    {"name":"BarberFlow","description":"Agendamento",
                     "originalIdea":"Plataforma de agendamento para barbearias"}
                    """)
                .andExpect(status().isCreated()));

    alice.send("/api/projects/" + projectId + "/roadmap", null).andExpect(status().isCreated());

    String phaseId =
        alice.idFrom(
            alice
                .send(
                    "/api/projects/" + projectId + "/roadmap/phases",
                    "{\"position\":1,\"title\":\"Core Implementation\"}")
                .andExpect(status().isCreated()));

    String taskId =
        alice.idFrom(
            alice
                .send(
                    "/api/projects/" + projectId + "/roadmap/phases/" + phaseId + "/tasks",
                    """
                    {"position":1,"title":"Payment Integration","objective":"Integrar pagamentos",
                     "riskLevel":"HIGH"}
                    """)
                .andExpect(status().isCreated()));

    // 2. Alice posts evidence with build success but exposed synthetic OpenAI token
    String evidenceContent =
        """
        BUILD SUCCESS
        Tests run: 12, Failures: 0
        OPENAI_API_KEY=sk-examplesynthetictestingsecretkey12345
        """;

    alice
        .send(
            "/api/projects/" + projectId + "/tasks/" + taskId + "/evidence",
            """
            {"type":"BUILD_RESULT","rawContent":"%s","source":"ci"}
            """
                .formatted(evidenceContent.replace("\n", "\\n")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.analysis.status").value("SUCCESS"));

    // 3. Security gate is BLOCKED and finding is CRITICAL
    alice
        .read("/api/projects/" + projectId + "/security")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.critical").value(1))
        .andExpect(jsonPath("$.gateStatus").value("BLOCKED"))
        .andExpect(jsonPath("$.canProceed").value(false));

    // 4. NextStep is forced to REVIEW_SECURITY
    alice
        .read("/api/projects/" + projectId + "/next-step")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("REVIEW_SECURITY"));

    // 5. PromptBuilder blocks copying and redacts secret
    String promptResponse =
        alice
            .send("/api/projects/" + projectId + "/prompts/generate", "{}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.securityStatus").value("BLOCKED"))
            .andExpect(jsonPath("$.copyAllowed").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(promptResponse).doesNotContain("sk-examplesynthetictestingsecretkey12345");
    assertThat(promptResponse).contains("sk-****REDACTED****");

    // 6. Remediation: Alice sends safe evidence
    String safeEvidence =
        """
        BUILD SUCCESS
        Tests run: 12, Failures: 0
        OPENAI_API_KEY=${OPENAI_API_KEY}
        """;
    alice
        .send(
            "/api/projects/" + projectId + "/tasks/" + taskId + "/evidence",
            """
            {"type":"BUILD_RESULT","rawContent":"%s","source":"ci"}
            """
                .formatted(safeEvidence.replace("\n", "\\n")))
        .andExpect(status().isCreated());

    // Finding is NOT resolved automatically (as required by specification)
    alice
        .read("/api/projects/" + projectId + "/security")
        .andExpect(jsonPath("$.gateStatus").value("BLOCKED"));

    // Alice explicitly resolves every open finding.
    //
    // THIS LOOP USED TO RESOLVE findings.get(0) AND NOTHING ELSE, and it passed because the
    // offending line raised exactly one finding. SEC-002 carried a stale copy of the redactor's
    // pre-CTX-09B-1 key expression, whose \b could not fire before API_KEY in OPENAI_API_KEY, so
    // "OPENAI_API_KEY=sk-…" was seen only by SEC-003. Aligning SEC-002 to the shared key vocabulary
    // makes it see that line too, and one hardcoded credential now raises two findings: SEC-003
    // CRITICAL, which names the provider from the value's shape, and SEC-002 HIGH, which names the
    // assignment. Both are true, both carry redacted evidence, and resolving one no longer clears
    // the gate — it went to REQUIRES_APPROVAL, which is the gate working, not a regression.
    //
    // Resolving all of them is also the more honest fixture: "Alice resolves the findings" is what
    // the step says it does, and indexing element zero silently asserted that there would never be
    // a second one.
    JsonNode findings =
        json.readTree(
            alice
                .read("/api/projects/" + projectId + "/security/findings")
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(findings.size()).isGreaterThanOrEqualTo(1);
    String findingId = findings.get(0).get("id").asText();

    for (JsonNode finding : findings) {
      alice
          .send(
              "/api/projects/"
                  + projectId
                  + "/security/findings/"
                  + finding.get("id").asText()
                  + "/resolve",
              "{\"reason\":\"Token removido e rotacionado no provedor.\"}")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    // Security Gate now passes
    alice
        .read("/api/projects/" + projectId + "/security")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gateStatus").value("PASS"))
        .andExpect(jsonPath("$.canProceed").value(true));

    // 7. Bob attempts unauthorized access
    Session bob = new Session("bob");

    bob.read("/api/projects/" + projectId + "/security").andExpect(status().isNotFound());
    bob.read("/api/projects/" + projectId + "/security/findings").andExpect(status().isNotFound());
    bob.read("/api/projects/" + projectId + "/audit").andExpect(status().isNotFound());
    bob.send(
            "/api/projects/" + projectId + "/security/findings/" + findingId + "/acknowledge",
            null)
        .andExpect(status().isNotFound());
    bob.send("/api/projects/" + projectId + "/security/findings/" + findingId + "/resolve", null)
        .andExpect(status().isNotFound());

    // 8. Audit trail on Alice contains all logged actions
    JsonNode audit =
        json.readTree(
            alice
                .read("/api/projects/" + projectId + "/audit")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(audit.size()).isGreaterThanOrEqualTo(3);
  }
}

