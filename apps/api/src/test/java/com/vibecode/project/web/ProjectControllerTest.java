package com.vibecode.project.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectControllerTest {

  @Autowired MockMvc mvc;

  @Test
  void createsAProject() throws Exception {
    mvc.perform(
            post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Atlas","description":"A reliable product",
                     "originalIdea":"Track my build with durable memory"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.name").value("Atlas"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.currentPhase").value("Foundation"));
  }

  @Test
  void rejectsAProjectWithoutTheOriginalIdea() throws Exception {
    mvc.perform(
            post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Atlas\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.violations[0].field").value("originalIdea"));
  }

  @Test
  void rejectsABlankName() throws Exception {
    mvc.perform(
            post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \",\"originalIdea\":\"An idea\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void readsBackACreatedProject() throws Exception {
    String id =
        ProjectTestSupport.createProject(mvc, "Readable", "An idea worth remembering");

    mvc.perform(get("/api/projects/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.name").value("Readable"));

    mvc.perform(get("/api/projects")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
  }

  @Test
  void returnsNotFoundForAnUnknownProject() throws Exception {
    mvc.perform(get("/api/projects/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
