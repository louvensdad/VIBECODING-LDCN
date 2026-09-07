package com.vibecode.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

  @Test
  void malformedJsonIsARequestErrorNotAServerError() throws Exception {
    mvc.perform(
            post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
  }

  @Test
  void anUnsupportedMethodKeepsItsOwnStatus() throws Exception {
    mvc.perform(delete("/api/projects")).andExpect(status().isMethodNotAllowed());
  }

  @Test
  void anUnknownRouteIsNotReportedAsAServerError() throws Exception {
    mvc.perform(get("/api/does-not-exist")).andExpect(status().is4xxClientError());
  }

  @Test
  void everyErrorUsesTheSameShape() throws Exception {
    mvc.perform(
            post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").isNotEmpty())
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.violations").isArray());
  }
}
