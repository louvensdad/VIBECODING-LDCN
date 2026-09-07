package com.vibecode.project.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.domain.User;
import com.vibecode.support.TestIdentity;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Creates real projects over the API so other modules can test against one that actually exists and
 * has an owner.
 */
public final class ProjectTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  private ProjectTestSupport() {}

  /** Creates a project owned by {@code owner}, through the real authenticated endpoint. */
  public static String createProject(MockMvc mvc, User owner, String name, String idea)
      throws Exception {
    String body =
        JSON.writeValueAsString(new CreateProjectRequest(name, "Created by a test", idea));
    String response =
        mvc.perform(
                post("/api/projects")
                    .with(TestIdentity.as(owner))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode node = JSON.readTree(response);
    return node.get("id").asText();
  }
}
