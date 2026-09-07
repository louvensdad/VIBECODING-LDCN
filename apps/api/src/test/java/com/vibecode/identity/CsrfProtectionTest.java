package com.vibecode.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.domain.User;
import com.vibecode.support.TestIdentity;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * CSRF protection, exercised through the real token flow rather than switched off.
 *
 * <p>The point of the pairing is that a cross-site page can make the browser send the cookie but
 * cannot read it, so it cannot produce the matching header.
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
class CsrfProtectionTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TestIdentity identity;

  private User owner;
  private MockHttpSession session;
  private Cookie csrfCookie;
  private String csrfToken;

  @BeforeEach
  void setUp() throws Exception {
    owner = identity.createUser("CsrfOwner");
    session = new MockHttpSession();
    MvcResult result = mvc.perform(get("/api/auth/csrf").session(session)).andReturn();
    csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
    csrfToken = json.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }

  private static final String PROJECT_BODY =
      """
      {"name":"CSRF","originalIdea":"Uma ideia qualquer"}
      """;

  @Test
  @DisplayName("the token endpoint hands out both a cookie and a matching value")
  void csrfEndpointIssuesAToken() {
    assertThat(csrfToken).isNotBlank();
    assertThat(csrfCookie).isNotNull();
    assertThat(csrfCookie.getValue()).isEqualTo(csrfToken);
    // Readable by JavaScript on purpose: the client must echo it in a header.
    assertThat(csrfCookie.isHttpOnly()).isFalse();
  }

  @Test
  @DisplayName("an authenticated POST without a CSRF token is refused")
  void unsafeRequestWithoutTokenIsBlocked() throws Exception {
    mvc.perform(
            post("/api/projects")
                .session(session)
                .with(TestIdentity.as(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROJECT_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  @DisplayName("a POST carrying the cookie but a wrong header is refused")
  void unsafeRequestWithWrongTokenIsBlocked() throws Exception {
    mvc.perform(
            post("/api/projects")
                .session(session)
                .with(TestIdentity.as(owner))
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROJECT_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("an authenticated POST with the matching token is allowed")
  void unsafeRequestWithValidTokenIsAllowed() throws Exception {
    mvc.perform(
            post("/api/projects")
                .session(session)
                .with(TestIdentity.as(owner))
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROJECT_BODY))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("logout is protected too: a forced logout is a real nuisance attack")
  void logoutRequiresACsrfToken() throws Exception {
    mvc.perform(post("/api/auth/logout").session(session).with(TestIdentity.as(owner)))
        .andExpect(status().isForbidden());

    mvc.perform(
            post("/api/auth/logout")
                .session(session)
                .with(TestIdentity.as(owner))
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfToken))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("a safe request needs no token")
  void safeRequestsAreNotBlocked() throws Exception {
    mvc.perform(get("/api/projects").session(session).with(TestIdentity.as(owner)))
        .andExpect(status().isOk());
  }
}
