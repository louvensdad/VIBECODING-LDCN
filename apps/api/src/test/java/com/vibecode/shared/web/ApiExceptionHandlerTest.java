package com.vibecode.shared.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vibecode.identity.domain.User;
import com.vibecode.support.TestIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class ApiExceptionHandlerTest {

  @Autowired MockMvc mvc;
  @Autowired ApiExceptionHandler handler;
  @Autowired TestIdentity identity;

  private User caller;

  @BeforeEach
  void signIn() {
    // These tests are about error mapping, so they authenticate first: an unauthenticated call
    // would stop at 401 and never reach the handler under test.
    caller = identity.createUser("ErrorCaller");
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postAs(
      String url) {
    return post(url).with(TestIdentity.as(caller)).with(csrf());
  }

  /**
   * What the handler decided it can write error bodies as.
   *
   * <p>Pinned because the {@code Accept} check is only as correct as this list. It is derived from
   * the message converters rather than written down, which is what makes it follow a converter
   * being added or replaced — and also what makes it worth pinning: a converter that claimed a
   * wildcard would widen this to everything, quietly turn the check into a no-op and bring back the
   * unbounded stack trace this whole task removed, without a single other test changing colour.
   *
   * <p>The current value is Jackson's two: comparing against only the first of them is the defect
   * review found, and it cost every {@code application/problem+json} caller their error body.
   */
  @Test
  void theHandlerWritesErrorBodiesAsExactlyWhatTheConvertersAdvertise() {
    assertThat(handler.writableErrorTypes())
        .containsExactly(MediaType.APPLICATION_JSON, MediaType.parseMediaType("application/*+json"));
    assertThat(handler.writableErrorTypes())
        .as("a wildcard here would silently disable the Accept check entirely")
        .noneMatch(MediaType::isWildcardType);
  }

  @Test
  void malformedJsonIsARequestErrorNotAServerError() throws Exception {
    mvc.perform(
            postAs("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
  }

  @Test
  void anUnsupportedMethodKeepsItsOwnStatus() throws Exception {
    mvc.perform(delete("/api/projects").with(TestIdentity.as(caller)).with(csrf()))
        .andExpect(status().isMethodNotAllowed());
  }

  @Test
  void anUnknownRouteIsNotReportedAsAServerError() throws Exception {
    mvc.perform(get("/api/does-not-exist").with(TestIdentity.as(caller)))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void everyErrorUsesTheSameShape() throws Exception {
    mvc.perform(
            postAs("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").isNotEmpty())
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.violations").isArray());
  }
}
