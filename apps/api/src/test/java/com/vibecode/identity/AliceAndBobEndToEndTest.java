package com.vibecode.identity;

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
 * The acceptance case for this phase, driven entirely through real sessions.
 *
 * <p>No test post-processor authenticates anyone here: Alice and Bob register and log in over HTTP,
 * carry their own session cookies, and send their own CSRF tokens — the same path a browser takes.
 *
 * <p>Runs in its own application context: Spring Security's {@code csrf()} request post-processor
 * swaps the token repository inside the shared {@code CsrfFilter} bean, and the test context is
 * cached across classes — so once any class uses it, the real {@code CookieCsrfTokenRepository}
 * stops issuing the XSRF-TOKEN cookie for everyone else. A context of its own keeps the genuine
 * cookie-and-header flow intact instead of weakening it to make the tests agree.
 */
@TestPropertySource(properties = "vibecode.test.context=real-csrf-flow")
@SpringBootTest
@AutoConfigureMockMvc
class AliceAndBobEndToEndTest {

  private static final String PASSWORD = "uma senha suficientemente longa";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  /** A signed-in browser: its own session, its own CSRF token. */
  private final class Session {

    final MockHttpSession session = new MockHttpSession();
    final String email;
    Cookie csrfCookie;
    String csrfToken;

    Session(String name) throws Exception {
      this.email = name + "-" + UUID.randomUUID() + "@example.com";
      refreshCsrf();
      send("/api/auth/register",
              """
              {"email":"%s","password":"%s","displayName":"%s"}
              """
                  .formatted(email, PASSWORD, name))
          .andExpect(status().isCreated());
      send("/api/auth/login",
              "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))
          .andExpect(status().isOk());
    }

    void refreshCsrf() throws Exception {
      MvcResult result = mvc.perform(csrfRequest().session(session)).andReturn();
      csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
      csrfToken = json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
      request = request.session(session).header("X-XSRF-TOKEN", csrfToken);
      return csrfCookie == null ? request : request.cookie(csrfCookie);
    }

    /** An authenticated POST carrying this session's CSRF token. */
    ResultActions send(String url, String body) throws Exception {
      MockHttpServletRequestBuilder request =
          authed(post(url)).contentType(MediaType.APPLICATION_JSON);
      return mvc.perform(body == null ? request : request.content(body));
    }

    /** An authenticated GET. Safe methods carry no token. */
    ResultActions read(String url) throws Exception {
      return mvc.perform(get(url).session(session));
    }

    String idFrom(ResultActions actions) throws Exception {
      JsonNode node = json.readTree(actions.andReturn().getResponse().getContentAsString());
      return node.get("id").asText();
    }
  }

  private static MockHttpServletRequestBuilder csrfRequest() {
    return get("/api/auth/csrf");
  }

  @Test
  @DisplayName("Alice builds her project; Bob, holding every id, gets nothing")
  void aliceAndBob() throws Exception {
    // --- Alice: register, log in, and build a project end to end -----------------------------
    Session alice = new Session("alice");

    alice.read("/api/auth/me").andExpect(status().isOk()).andExpect(jsonPath("$.email").value(alice.email));

    String project =
        alice.idFrom(
            alice
                .send(
                    "/api/projects",
                    """
                    {"name":"BarberFlow","description":"Agendamento",
                     "originalIdea":"Plataforma de agendamento para barbearias"}
                    """)
                .andExpect(status().isCreated()));

    alice.send("/api/projects/" + project + "/roadmap", null).andExpect(status().isCreated());
    String phase =
        alice.idFrom(
            alice
                .send(
                    "/api/projects/" + project + "/roadmap/phases",
                    "{\"position\":1,\"title\":\"Authentication\"}")
                .andExpect(status().isCreated()));
    String task =
        alice.idFrom(
            alice
                .send(
                    "/api/projects/" + project + "/roadmap/phases/" + phase + "/tasks",
                    """
                    {"position":1,"title":"Create User","objective":"Criar a entidade User",
                     "riskLevel":"MEDIUM"}
                    """)
                .andExpect(status().isCreated()));

    alice
        .send(
            "/api/projects/" + project + "/tasks/" + task + "/evidence",
            """
            {"type":"BUILD_RESULT","rawContent":"BUILD FAILURE\\nCompilation error in User.java",
             "source":"maven"}
            """)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.analysis.status").value("FAILURE"));

    alice
        .read("/api/projects/" + project + "/next-step")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("FIX_ERROR"));

    String prompt =
        alice
            .send("/api/projects/" + project + "/prompts/generate", "{}")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(prompt).contains("Compilation error in User.java").contains("BarberFlow");

    // --- Bob: register, log in, and try every id he now knows ---------------------------------
    Session bob = new Session("bob");

    bob.read("/api/projects")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    for (String url :
        java.util.List.of(
            "/api/projects/" + project,
            "/api/projects/" + project + "/brain",
            "/api/projects/" + project + "/roadmap",
            "/api/projects/" + project + "/state",
            "/api/projects/" + project + "/guide",
            "/api/projects/" + project + "/next-step",
            "/api/projects/" + project + "/tasks/" + task,
            "/api/projects/" + project + "/tasks/" + task + "/evidence")) {
      String body =
          bob.read(url)
              .andExpect(status().isNotFound())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body).doesNotContain("BarberFlow").doesNotContain("Create User");
    }

    bob.send(
            "/api/projects/" + project + "/tasks/" + task + "/evidence",
            """
            {"type":"BUILD_RESULT","rawContent":"BUILD SUCCESS","source":"bob"}
            """)
        .andExpect(status().isNotFound());
    bob.send("/api/projects/" + project + "/prompts/generate", "{}")
        .andExpect(status().isNotFound());

    // --- Alice again: her project is exactly as she left it ------------------------------------
    alice
        .read("/api/projects/" + project + "/state")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalTasks").value(1))
        .andExpect(jsonPath("$.completedTasks").value(0));
    alice
        .read("/api/projects/" + project + "/tasks/" + task + "/evidence")
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].source").value("maven"));
    alice
        .read("/api/projects/" + project + "/next-step")
        .andExpect(jsonPath("$.type").value("FIX_ERROR"));

    // --- Logout ends Alice's access -----------------------------------------------------------
    alice.send("/api/auth/logout", null).andExpect(status().isNoContent());
    alice.read("/api/projects/" + project).andExpect(status().isUnauthorized());
  }
}
