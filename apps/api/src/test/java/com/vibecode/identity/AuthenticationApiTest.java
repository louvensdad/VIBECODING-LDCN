package com.vibecode.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.domain.User;
import com.vibecode.identity.domain.UserStatus;
import com.vibecode.identity.infrastructure.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * The real authentication flow, driven exactly as a browser would: fetch a CSRF token, send it back
 * as a header, and carry the session cookie between requests.
 *
 * <p>Nothing here disables CSRF or stubs the security chain.
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
class AuthenticationApiTest {

  private static final String PASSWORD = "a long enough passphrase";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;

  /** One browser: a session plus the CSRF token and cookie it was handed. */
  private final class Browser {

    final MockHttpSession session = new MockHttpSession();
    Cookie csrfCookie;
    String csrfToken;

    Browser() throws Exception {
      MvcResult result =
          mvc.perform(get("/api/auth/csrf").session(session))
              .andExpect(status().isOk())
              .andReturn();
      csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
      JsonNode body = json.readTree(result.getResponse().getContentAsString());
      csrfToken = body.get("token").asText();
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(
        String url, String payload) {
      var request =
          post(url)
              .session(session)
              .header("X-XSRF-TOKEN", csrfToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content(payload);
      if (csrfCookie != null) {
        request = request.cookie(csrfCookie);
      }
      return request;
    }

    String register(String email) throws Exception {
      return mvc.perform(
              postJson(
                  "/api/auth/register",
                  """
                  {"email":"%s","password":"%s","displayName":"Test"}
                  """
                      .formatted(email, PASSWORD)))
          .andExpect(status().isCreated())
          .andReturn()
          .getResponse()
          .getContentAsString();
    }

    void login(String email) throws Exception {
      mvc.perform(
              postJson(
                  "/api/auth/login",
                  "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
          .andExpect(status().isOk());
    }
  }

  private String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@example.com";
  }

  @Nested
  @DisplayName("registration")
  class Registration {

    @Test
    void registersAndNeverReturnsTheHash() throws Exception {
      Browser browser = new Browser();
      String email = uniqueEmail();

      String body = browser.register(email);

      assertThat(body)
          .contains(email)
          .doesNotContain("passwordHash")
          .doesNotContain("password")
          .doesNotContain(PASSWORD);
      JsonNode node = json.readTree(body);
      assertThat(node.get("role").asText()).isEqualTo("USER");
      assertThat(node.has("passwordHash")).isFalse();
    }

    @Test
    @DisplayName("the same address cannot register twice, in any casing")
    void rejectsDuplicateEmail() throws Exception {
      Browser browser = new Browser();
      String email = uniqueEmail();
      browser.register(email);

      mvc.perform(
              browser.postJson(
                  "/api/auth/register",
                  """
                  {"email":"%s","password":"%s","displayName":"Dup"}
                  """
                      .formatted(email.toUpperCase(java.util.Locale.ROOT), PASSWORD)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void rejectsAMalformedEmail() throws Exception {
      Browser browser = new Browser();

      mvc.perform(
              browser.postJson(
                  "/api/auth/register",
                  "{\"email\":\"not-an-email\",\"password\":\"%s\"}".formatted(PASSWORD)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("a short password is refused, and the response does not echo it")
    void rejectsAShortPassword() throws Exception {
      Browser browser = new Browser();

      mvc.perform(
              browser.postJson(
                  "/api/auth/register",
                  "{\"email\":\"%s\",\"password\":\"short\"}".formatted(uniqueEmail())))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"))
          .andExpect(content().string(org.hamcrest.Matchers.not(
              org.hamcrest.Matchers.containsString("short"))));
    }

    @Test
    @DisplayName("a passphrase with spaces and accents is accepted")
    void acceptsAPassphrase() throws Exception {
      Browser browser = new Browser();
      String email = uniqueEmail();

      mvc.perform(
              browser.postJson(
                  "/api/auth/register",
                  """
                  {"email":"%s","password":"minha frase secreta é longa","displayName":"Ana"}
                  """
                      .formatted(email)))
          .andExpect(status().isCreated());
    }
  }

  @Nested
  @DisplayName("login")
  class Login {

    @Test
    void logsInAndReportsTheUser() throws Exception {
      Browser browser = new Browser();
      String email = uniqueEmail();
      browser.register(email);

      mvc.perform(
              browser.postJson(
                  "/api/auth/login",
                  "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.email").value(email))
          .andExpect(jsonPath("$.role").value("USER"))
          .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("a wrong password and an unknown address are indistinguishable")
    void doesNotRevealWhichAccountsExist() throws Exception {
      Browser browser = new Browser();
      String email = uniqueEmail();
      browser.register(email);

      String wrongPassword =
          mvc.perform(
                  browser.postJson(
                      "/api/auth/login",
                      "{\"email\":\"%s\",\"password\":\"definitely wrong here\"}".formatted(email)))
              .andExpect(status().isUnauthorized())
              .andReturn()
              .getResponse()
              .getContentAsString();

      String unknownAccount =
          mvc.perform(
                  browser.postJson(
                      "/api/auth/login",
                      "{\"email\":\"%s\",\"password\":\"%s\"}"
                          .formatted(uniqueEmail(), PASSWORD)))
              .andExpect(status().isUnauthorized())
              .andReturn()
              .getResponse()
              .getContentAsString();

      // Same code and same message: the response cannot be used to test which addresses exist.
      assertThat(json.readTree(wrongPassword).get("code").asText())
          .isEqualTo(json.readTree(unknownAccount).get("code").asText());
      assertThat(json.readTree(wrongPassword).get("message").asText())
          .isEqualTo(json.readTree(unknownAccount).get("message").asText());
    }

    @Test
    @DisplayName("a disabled account cannot sign in, and is not told why")
    void refusesDisabledAccounts() throws Exception {
      Browser browser = new Browser();
      String email = uniqueEmail();
      browser.register(email);

      User user = users.findByEmail(email).orElseThrow();
      user.changeStatus(UserStatus.DISABLED);
      users.save(user);

      mvc.perform(
              browser.postJson(
                  "/api/auth/login",
                  "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.message").value("Credenciais inválidas."));
    }
  }

  @Nested
  @DisplayName("me and logout")
  class Session {

    @Test
    @DisplayName("login creates a session the next request recognises, and logout ends it")
    void sessionLifecycle() throws Exception {
      Browser browser = new Browser();
      String email = uniqueEmail();
      browser.register(email);
      browser.login(email);

      // The very next request, carrying only the session, is recognised.
      mvc.perform(get("/api/auth/me").session(browser.session))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.email").value(email));

      mvc.perform(browser.postJson("/api/auth/logout", "")).andExpect(status().isNoContent());

      // The same session no longer authenticates anything.
      mvc.perform(get("/api/auth/me").session(browser.session))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void meRequiresAuthentication() throws Exception {
      mvc.perform(get("/api/auth/me"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
  }
}
