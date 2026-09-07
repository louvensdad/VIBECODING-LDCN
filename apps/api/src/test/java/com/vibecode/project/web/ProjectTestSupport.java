package com.vibecode.project.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Creates real projects over the API so other modules can test against an existing one. */
public final class ProjectTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  private ProjectTestSupport() {}

  public static String createProject(MockMvc mvc, String name, String idea) throws Exception {
    String body =
        JSON.writeValueAsString(new CreateProjectRequest(name, "Created by a test", idea));
    String response =
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode node = JSON.readTree(response);
    return node.get("id").asText();
  }
}
