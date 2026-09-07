package com.vibecode.brain.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vibecode.project.web.ProjectTestSupport;
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
class BrainControllerTest {

  @Autowired MockMvc mvc;
  @Autowired TestIdentity identity;

  private User owner;

  @BeforeEach
  void signIn() {
    owner = identity.createUser("BrainOwner");
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getAs(
      String url, Object... vars) {
    return get(url, vars).with(TestIdentity.as(owner));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postAs(
      String url, Object... vars) {
    return post(url, vars).with(TestIdentity.as(owner)).with(csrf());
  }

  @Test
  void writesAndReadsBackOfficialMemory() throws Exception {
    String project = ProjectTestSupport.createProject(mvc, owner, "Brain test", "Remember my decisions");

    mvc.perform(
            postAs("/api/projects/{id}/brain/entries", project)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"type":"VISION","title":"Core vision",
                     "content":"The project owns its own context","source":"user"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.source").value("user"));

    mvc.perform(
            postAs("/api/projects/{id}/brain/entries", project)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"type":"DECISION","title":"Modular monolith",
                     "content":"Start simple, extract later","source":"user"}
                    """))
        .andExpect(status().isCreated());

    mvc.perform(getAs("/api/projects/{id}/brain", project))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").value(project))
        .andExpect(jsonPath("$.entryCount").value(2))
        .andExpect(jsonPath("$.countByType.VISION").value(1))
        .andExpect(jsonPath("$.entries").isArray());
  }

  @Test
  void rejectsAnEntryWithoutASource() throws Exception {
    String project = ProjectTestSupport.createProject(mvc, owner, "Provenance", "Memory needs a source");

    mvc.perform(
            postAs("/api/projects/{id}/brain/entries", project)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"NOTE\",\"title\":\"No source\",\"content\":\"Anonymous\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejectsAnUnknownEntryType() throws Exception {
    String project = ProjectTestSupport.createProject(mvc, owner, "Types", "Only known types");

    mvc.perform(
            postAs("/api/projects/{id}/brain/entries", project)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"type":"HALLUCINATION","title":"Nope","content":"Invalid","source":"user"}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void memoryCannotBeWrittenToAProjectThatDoesNotExist() throws Exception {
    mvc.perform(
            postAs("/api/projects/{id}/brain/entries", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"type":"NOTE","title":"Orphan","content":"Nowhere","source":"user"}
                    """))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
