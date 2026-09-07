package com.vibecode.project.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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
class ProjectControllerTest {

  @Autowired MockMvc mvc;
  @Autowired TestIdentity identity;

  private User owner;

  @BeforeEach
  void signIn() {
    owner = identity.createUser("Owner");
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getAs(
      String url, Object... vars) {
    return get(url, vars).with(TestIdentity.as(owner));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postAs(
      String url) {
    return post(url).with(TestIdentity.as(owner)).with(csrf());
  }

  @Test
  void createsAProject() throws Exception {
    mvc.perform(
            postAs("/api/projects")
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
            postAs("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Atlas\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.violations[0].field").value("originalIdea"));
  }

  @Test
  void rejectsABlankName() throws Exception {
    mvc.perform(
            postAs("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \",\"originalIdea\":\"An idea\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void readsBackACreatedProject() throws Exception {
    String id =
        ProjectTestSupport.createProject(mvc, owner, "Readable", "An idea worth remembering");

    mvc.perform(getAs("/api/projects/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.name").value("Readable"));

    mvc.perform(getAs("/api/projects")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
  }

  @Test
  void returnsNotFoundForAnUnknownProject() throws Exception {
    mvc.perform(getAs("/api/projects/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
