package com.vibecode.output.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
class OutputControllerTest {

  @Autowired MockMvc mvc;
  @Autowired TestIdentity identity;

  private User owner;

  @BeforeEach
  void signIn() {
    owner = identity.createUser("OutputOwner");
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postAs(
      String url, Object... vars) {
    return post(url, vars).with(TestIdentity.as(owner)).with(csrf());
  }

  @Test
  void analyzesASuccessfulBuild() throws Exception {
    String project = ProjectTestSupport.createProject(mvc, owner, "Analyzer", "Analyze my outputs");

    mvc.perform(
            postAs("/api/projects/{id}/outputs/analyze", project)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"[INFO] BUILD SUCCESS\",\"kind\":\"BUILD\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.shouldContinue").value(true))
        .andExpect(jsonPath("$.requiresCorrection").value(false))
        .andExpect(jsonPath("$.signals[0]").value("BUILD_SUCCESS"))
        .andExpect(jsonPath("$.kind").value("BUILD"));
  }

  @Test
  void refusesToAdvanceOnAnUnverifiedClaim() throws Exception {
    String project = ProjectTestSupport.createProject(mvc, owner, "Claims", "Do not trust claims");

    mvc.perform(
            postAs("/api/projects/{id}/outputs/analyze", project)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Pronto, tudo concluído com sucesso!\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NEEDS_VALIDATION"))
        .andExpect(jsonPath("$.shouldContinue").value(false))
        .andExpect(jsonPath("$.kind").value("GENERIC_TEXT"));
  }

  @Test
  void rejectsAnEmptyOutput() throws Exception {
    String project = ProjectTestSupport.createProject(mvc, owner, "Empty", "Reject empty outputs");

    mvc.perform(
            postAs("/api/projects/{id}/outputs/analyze", project)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void outputsBelongToAnExistingProject() throws Exception {
    mvc.perform(
            postAs("/api/projects/{id}/outputs/analyze", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"BUILD SUCCESS\"}"))
        .andExpect(status().isNotFound());
  }
}
