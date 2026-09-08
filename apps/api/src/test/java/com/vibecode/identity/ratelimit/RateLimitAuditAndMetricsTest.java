package com.vibecode.identity.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vibecode.audit.domain.AuditEvent;
import com.vibecode.audit.domain.AuditEventType;
import com.vibecode.audit.infrastructure.AuditEventRepository;
import com.vibecode.identity.application.IdentityService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** What throttling leaves behind: an audit record, a metric, and nothing sensitive. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "vibecode.test.context=rate-limit-audit",
      "vibecode.security.auth-rate-limit.login.identifier.capacity=2",
      "vibecode.security.auth-rate-limit.login.identifier.window=15m",
      "vibecode.security.auth-rate-limit.login.origin.capacity=50",
      "vibecode.security.auth-rate-limit.login.origin.window=15m",
      "vibecode.security.auth-rate-limit.register.identifier.capacity=1",
      "vibecode.security.auth-rate-limit.register.identifier.window=1h",
      "vibecode.security.auth-rate-limit.register.origin.capacity=50",
      "vibecode.security.auth-rate-limit.register.origin.window=1h"
    })
class RateLimitAuditAndMetricsTest {

  /** The fixture from the phase brief. Neither value may ever reach a log, audit row or metric. */
  private static final String FIXTURE_EMAIL = "rate-test-user@example.invalid";
  private static final String FIXTURE_PASSWORD = "NeverLogThisPassword8472!";

  @Autowired MockMvc mvc;
  @Autowired AuditEventRepository auditEvents;
  @Autowired MeterRegistry meters;
  @Autowired IdentityService identity;

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

  private List<AuditEvent> eventsOfType(AuditEventType type) {
    return auditEvents.findAll().stream()
        .filter(event -> event.getEventType() == type)
        .toList();
  }

  @Test
  @DisplayName("hitting the limit is audited, and survives the request that threw")
  void throttlingIsAudited() throws Exception {
    String email = "audit-" + UUID.randomUUID() + "@example.com";

    login(email, "wrong one", "203.0.113.20").andExpect(status().isUnauthorized());
    login(email, "wrong two", "203.0.113.20").andExpect(status().isUnauthorized());
    login(email, "wrong three", "203.0.113.20").andExpect(status().isTooManyRequests());

    List<AuditEvent> throttled = eventsOfType(AuditEventType.AUTH_RATE_LIMITED);

    // The refusal is raised as an exception; if the audit write shared that transaction it would
    // be rolled back and this list would be empty. That is the phase 4 defect, guarded here.
    assertThat(throttled).as("o evento de throttling deveria persistir").isNotEmpty();
    AuditEvent event = throttled.get(throttled.size() - 1);
    assertThat(event.getResult()).isEqualTo("DENIED");
    assertThat(event.getTargetType()).isEqualTo("AUTH_ENDPOINT");
    assertThat(event.getMetadata()).contains("policyId=login-account");
  }

  @Test
  @DisplayName("a failed login is still audited separately, and is not replaced by the throttle")
  void loginFailureAuditIsPreserved() throws Exception {
    String email = "failure-" + UUID.randomUUID() + "@example.com";
    identity.register(email, "uma senha suficientemente longa", "Failing");

    int failuresBefore = eventsOfType(AuditEventType.LOGIN_FAILURE).size();

    login(email, "wrong password here", "203.0.113.21").andExpect(status().isUnauthorized());

    assertThat(eventsOfType(AuditEventType.LOGIN_FAILURE))
        .as("uma tentativa real com senha errada continua sendo LOGIN_FAILURE")
        .hasSize(failuresBefore + 1);
  }

  @Test
  @DisplayName("registration throttling is audited under its own event type")
  void registrationThrottlingIsAudited() throws Exception {
    String email = "reg-" + UUID.randomUUID() + "@example.com";
    String body =
        """
        {"email":"%s","password":"uma senha suficientemente longa","displayName":"R"}
        """
            .formatted(email);

    mvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isTooManyRequests());

    assertThat(eventsOfType(AuditEventType.REGISTRATION_RATE_LIMITED)).isNotEmpty();
  }

  @Test
  @DisplayName("throttling events carry no identifier, address or credential")
  void auditCarriesNothingSensitive() throws Exception {
    login(FIXTURE_EMAIL, FIXTURE_PASSWORD, "203.0.113.99").andExpect(status().isUnauthorized());
    login(FIXTURE_EMAIL, FIXTURE_PASSWORD, "203.0.113.99").andExpect(status().isUnauthorized());
    login(FIXTURE_EMAIL, FIXTURE_PASSWORD, "203.0.113.99").andExpect(status().isTooManyRequests());

    // Absolute, for every event type: a password must never reach the trail.
    assertThat(flatten(auditEvents.findAll())).doesNotContain(FIXTURE_PASSWORD);

    // The throttling events specifically carry no identifier, no fingerprint and no address.
    // LOGIN_FAILURE does record the address that was tried — a deliberate decision from the
    // identity phase, because a burst of failures nobody can attribute to a target is not worth
    // recording. That decision is not revisited here.
    String throttlingRows =
        flatten(
            auditEvents.findAll().stream()
                .filter(
                    event ->
                        event.getEventType() == AuditEventType.AUTH_RATE_LIMITED
                            || event.getEventType() == AuditEventType.REGISTRATION_RATE_LIMITED)
                .toList());

    assertThat(throttlingRows).isNotBlank();
    assertThat(throttlingRows)
        .doesNotContain(FIXTURE_EMAIL)
        .doesNotContain(FIXTURE_PASSWORD)
        .doesNotContain("203.0.113.99")
        .doesNotContain("@");
  }

  private String flatten(List<AuditEvent> events) {
    return events.stream()
        .map(
            event ->
                String.join(
                    "|",
                    String.valueOf(event.getEventType()),
                    String.valueOf(event.getTargetType()),
                    String.valueOf(event.getTargetId()),
                    String.valueOf(event.getResult()),
                    String.valueOf(event.getMetadata())))
        .reduce("", (a, b) -> a + "\n" + b);
  }

  @Test
  @DisplayName("the denial counter is tagged by scope and policy only")
  void metricsHaveNoPersonalTags() throws Exception {
    String email = "metric-" + UUID.randomUUID() + "@example.com";

    login(email, "wrong", "203.0.113.30").andExpect(status().isUnauthorized());
    login(email, "wrong", "203.0.113.30").andExpect(status().isUnauthorized());
    login(email, "wrong", "203.0.113.30").andExpect(status().isTooManyRequests());

    var counters = meters.find("auth.rate_limit.denied").counters();
    assertThat(counters).as("o contador de negações deveria existir").isNotEmpty();

    double total = counters.stream().mapToDouble(counter -> counter.count()).sum();
    assertThat(total).isPositive();

    // High-cardinality or personal tags would make the metrics backend a data leak.
    counters.forEach(
        counter ->
            counter
                .getId()
                .getTags()
                .forEach(
                    tag -> {
                      assertThat(tag.getKey()).isIn("scope", "policy");
                      assertThat(tag.getValue()).doesNotContain("@").doesNotContain("203.0.113");
                    }));
  }
}
