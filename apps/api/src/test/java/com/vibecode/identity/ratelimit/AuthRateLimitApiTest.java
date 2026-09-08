package com.vibecode.identity.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.application.IdentityService;
import com.vibecode.support.MutableClock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Throttling over HTTP, with limits small enough to reach.
 *
 * <p>The canonical numbers for this phase: account 3, origin 6. Small limits make the two
 * dimensions visible — an origin allowance of 6 cannot give 3 attempts each to more than two
 * accounts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "vibecode.test.context=auth-rate-limit",
      "vibecode.security.auth-rate-limit.login.identifier.capacity=3",
      "vibecode.security.auth-rate-limit.login.identifier.window=15m",
      "vibecode.security.auth-rate-limit.login.origin.capacity=6",
      "vibecode.security.auth-rate-limit.login.origin.window=15m",
      "vibecode.security.auth-rate-limit.register.identifier.capacity=2",
      "vibecode.security.auth-rate-limit.register.identifier.window=1h",
      "vibecode.security.auth-rate-limit.register.origin.capacity=4",
      "vibecode.security.auth-rate-limit.register.origin.window=1h"
    })
class AuthRateLimitApiTest {

  private static final String PASSWORD = "uma senha suficientemente longa";
  private static final String WRONG_PASSWORD = "definitivamente a senha errada";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired IdentityService identity;
  @Autowired MutableClock clock;

  @BeforeEach
  void resetTime() {
    clock.reset();
  }

  private String uniqueEmail(String label) {
    return label + "-" + UUID.randomUUID() + "@example.com";
  }

  /** A login attempt from a given client address. */
  private ResultActions login(String email, String password, String origin) throws Exception {
    return mvc.perform(
        post("/api/auth/login")
            .with(csrf())
            .with(request -> {
              request.setRemoteAddr(origin);
              return request;
            })
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
  }

  private ResultActions register(String email, String origin) throws Exception {
    return mvc.perform(
        post("/api/auth/register")
            .with(csrf())
            .with(request -> {
              request.setRemoteAddr(origin);
              return request;
            })
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"email":"%s","password":"%s","displayName":"T"}
                """
                    .formatted(email, PASSWORD)));
  }

  @Nested
  @DisplayName("the account bucket")
  class AccountBucket {

    @Test
    @DisplayName("three wrong passwords are 401; the fourth attempt is 429")
    void accountLimitStopsGuessing() throws Exception {
      String email = uniqueEmail("victim");
      identity.register(email, PASSWORD, "Victim");

      for (int attempt = 1; attempt <= 3; attempt++) {
        login(email, WRONG_PASSWORD, "10.0.0.1")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
      }

      login(email, WRONG_PASSWORD, "10.0.0.1")
          .andExpect(status().isTooManyRequests())
          .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    @Test
    @DisplayName("an address with no account consumes its attempts just the same")
    void unknownAccountsAreThrottledToo() throws Exception {
      String neverRegistered = uniqueEmail("ghost");

      for (int attempt = 1; attempt <= 3; attempt++) {
        login(neverRegistered, PASSWORD, "10.0.0.2").andExpect(status().isUnauthorized());
      }
      login(neverRegistered, PASSWORD, "10.0.0.2").andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("attackers spread across many origins still exhaust one account's bucket")
    void distributedAttackOnOneAccountIsStopped() throws Exception {
      String email = uniqueEmail("alice");
      identity.register(email, PASSWORD, "Alice");

      // Each origin stays well under its own allowance of 6.
      login(email, WRONG_PASSWORD, "203.0.113.1").andExpect(status().isUnauthorized());
      login(email, WRONG_PASSWORD, "203.0.113.2").andExpect(status().isUnauthorized());
      login(email, WRONG_PASSWORD, "203.0.113.3").andExpect(status().isUnauthorized());

      // The account bucket does not care where the attempts came from.
      login(email, WRONG_PASSWORD, "203.0.113.4").andExpect(status().isTooManyRequests());
      login(email, WRONG_PASSWORD, "203.0.113.5").andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("the window reopens without the test sleeping through it")
    void bucketRecoversAfterTheWindow() throws Exception {
      String email = uniqueEmail("patient");
      identity.register(email, PASSWORD, "Patient");

      for (int attempt = 1; attempt <= 3; attempt++) {
        login(email, WRONG_PASSWORD, "10.0.0.3").andExpect(status().isUnauthorized());
      }
      login(email, WRONG_PASSWORD, "10.0.0.3").andExpect(status().isTooManyRequests());

      clock.advance(Duration.ofMinutes(20));

      login(email, PASSWORD, "10.0.0.3").andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("the origin bucket")
  class OriginBucket {

    @Test
    @DisplayName("one origin cannot get a fresh allowance per account it invents")
    void oneOriginAttackingManyAccountsIsCapped() throws Exception {
      String origin = "198.51.100.7";

      // Six different accounts, one attempt each: that is the whole origin allowance.
      for (int i = 1; i <= 6; i++) {
        login(uniqueEmail("target" + i), PASSWORD, origin).andExpect(status().isUnauthorized());
      }

      // A seventh name buys nothing: the account bucket is untouched, the origin bucket is empty.
      login(uniqueEmail("target7"), PASSWORD, origin).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("6 attempts, not 3 per account: the two limits are not multiplied together")
    void originLimitBoundsTotalVolume() throws Exception {
      String origin = "198.51.100.8";
      int allowed = 0;

      // Three accounts at three attempts each would be nine if the buckets combined naively.
      for (int account = 1; account <= 3; account++) {
        String email = uniqueEmail("acct" + account);
        for (int attempt = 1; attempt <= 3; attempt++) {
          int status = login(email, PASSWORD, origin).andReturn().getResponse().getStatus();
          if (status != 429) {
            allowed++;
          }
        }
      }

      assertThat(allowed).as("o bucket de origem deveria limitar o volume total").isEqualTo(6);
    }

    @Test
    @DisplayName("a successful login does not refill the origin bucket")
    void successDoesNotResetTheOriginAllowance() throws Exception {
      String origin = "198.51.100.9";
      String email = uniqueEmail("mixed");
      identity.register(email, PASSWORD, "Mixed");

      // Five failures against invented names, then a genuine login: six of six used.
      for (int i = 1; i <= 5; i++) {
        login(uniqueEmail("noise" + i), PASSWORD, origin).andExpect(status().isUnauthorized());
      }
      login(email, PASSWORD, origin).andExpect(status().isOk());

      // If a valid login cleared the origin bucket, an attacker holding one account could keep
      // buying themselves more guesses.
      login(uniqueEmail("noise6"), PASSWORD, origin).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("origins are independent of each other")
    void separateOriginsHaveSeparateAllowances() throws Exception {
      for (int i = 1; i <= 6; i++) {
        login(uniqueEmail("a" + i), PASSWORD, "192.0.2.10").andExpect(status().isUnauthorized());
      }
      login(uniqueEmail("a7"), PASSWORD, "192.0.2.10").andExpect(status().isTooManyRequests());

      // A different client is unaffected.
      login(uniqueEmail("b1"), PASSWORD, "192.0.2.11").andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("registration")
  class Registration {

    @Test
    @DisplayName("normal registration works, and the origin limit stops bulk creation")
    void registrationOriginLimit() throws Exception {
      String origin = "192.0.2.50";

      for (int i = 1; i <= 4; i++) {
        register(uniqueEmail("new" + i), origin).andExpect(status().isCreated());
      }
      register(uniqueEmail("new5"), origin)
          .andExpect(status().isTooManyRequests())
          .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    @Test
    @DisplayName("repeated attempts against one address are capped, duplicate or not")
    void registrationIdentifierLimit() throws Exception {
      String email = uniqueEmail("repeat");

      register(email, "192.0.2.51").andExpect(status().isCreated());
      // A second attempt on the same address is the 409 the product already returns.
      register(email, "192.0.2.52").andExpect(status().isConflict());
      // The third exhausts the identifier bucket, from a third address.
      register(email, "192.0.2.53").andExpect(status().isTooManyRequests());
    }
  }

  @Nested
  @DisplayName("what the response reveals")
  class Disclosure {

    @Test
    @DisplayName("throttled answers look the same whether or not the account exists")
    void throttlingDoesNotRevealExistence() throws Exception {
      String existing = uniqueEmail("real");
      identity.register(existing, PASSWORD, "Real");
      String missing = uniqueEmail("fake");

      // Before throttling: already indistinguishable.
      String beforeExisting = bodyOf(login(existing, WRONG_PASSWORD, "192.0.2.60"));
      String beforeMissing = bodyOf(login(missing, WRONG_PASSWORD, "192.0.2.61"));
      assertThat(codeOf(beforeExisting)).isEqualTo(codeOf(beforeMissing));
      assertThat(messageOf(beforeExisting)).isEqualTo(messageOf(beforeMissing));

      // Drain both account buckets.
      for (int i = 0; i < 3; i++) {
        login(existing, WRONG_PASSWORD, "192.0.2.62");
        login(missing, WRONG_PASSWORD, "192.0.2.63");
      }

      String throttledExisting = bodyOf(login(existing, WRONG_PASSWORD, "192.0.2.64"));
      String throttledMissing = bodyOf(login(missing, WRONG_PASSWORD, "192.0.2.65"));

      assertThat(codeOf(throttledExisting)).isEqualTo("TOO_MANY_REQUESTS");
      assertThat(codeOf(throttledExisting)).isEqualTo(codeOf(throttledMissing));
      assertThat(messageOf(throttledExisting)).isEqualTo(messageOf(throttledMissing));
    }

    @Test
    @DisplayName("the body carries no counter, no bucket name and no address")
    void responseLeaksNoInternals() throws Exception {
      String email = uniqueEmail("quiet");
      for (int i = 0; i < 4; i++) {
        login(email, WRONG_PASSWORD, "192.0.2.70");
      }

      String body = bodyOf(login(email, WRONG_PASSWORD, "192.0.2.70"));

      assertThat(body)
          .doesNotContain("remaining")
          .doesNotContain("attempts")
          .doesNotContain("LOGIN_ACCOUNT")
          .doesNotContain("LOGIN_ORIGIN")
          .doesNotContain("login-account")
          .doesNotContain("192.0.2.70")
          .doesNotContain(email);
    }

    private String bodyOf(ResultActions actions) throws Exception {
      return actions.andReturn().getResponse().getContentAsString();
    }

    private String codeOf(String body) throws Exception {
      return json.readTree(body).get("code").asText();
    }

    private String messageOf(String body) throws Exception {
      return json.readTree(body).get("message").asText();
    }
  }
}
