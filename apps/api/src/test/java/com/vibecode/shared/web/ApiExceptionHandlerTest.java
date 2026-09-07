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

@SpringBootTest
@AutoConfigureMockMvc
class ApiExceptionHandlerTest {

  @Autowired MockMvc mvc;
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
