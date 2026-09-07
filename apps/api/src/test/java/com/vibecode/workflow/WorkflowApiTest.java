package com.vibecode.workflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.project.web.ProjectTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The whole guided flow driven over HTTP, the way the web app drives it. */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private String project;
  private String phase;

  @BeforeEach
  void setUp() throws Exception {
    project = ProjectTestSupport.createProject(mvc, "ApiFlow", "Fluxo guiado via HTTP");
    mvc.perform(post("/api/projects/{id}/roadmap", project)).andExpect(status().isCreated());
    phase = idOf(postJson("/api/projects/" + project + "/roadmap/phases",
        """
        {"position":1,"title":"Authentication","description":"Login e sessão"}
        """));
  }

  private ResultActions postJson(String url, String body) throws Exception {
    return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private String idOf(ResultActions actions) throws Exception {
    JsonNode node = json.readTree(actions.andReturn().getResponse().getContentAsString());
    return node.get("id").asText();
  }

  private String addTask(int position, String title) throws Exception {
    return idOf(
        postJson(
                "/api/projects/" + project + "/roadmap/phases/" + phase + "/tasks",
                """
                {"position":%d,"title":"%s","objective":"Objetivo de %s","riskLevel":"MEDIUM"}
                """
                    .formatted(position, title, title))
            .andExpect(status().isCreated()));
  }

  @Test
  @DisplayName("the roadmap tree is served with derived phase and task status")
  void roadmapTree() throws Exception {
    addTask(1, "Create User");

    mvc.perform(get("/api/projects/{id}/roadmap", project))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalPhases").value(1))
        .andExpect(jsonPath("$.phases[0].title").value("Authentication"))
        .andExpect(jsonPath("$.phases[0].status").value("READY"))
        .andExpect(jsonPath("$.phases[0].totalTasks").value(1))
        .andExpect(jsonPath("$.phases[0].tasks[0].title").value("Create User"))
        .andExpect(jsonPath("$.phases[0].tasks[0].status").value("READY"));
  }

  @Test
  @DisplayName("a dependency keeps the dependent task out of the candidate list")
  void dependenciesOverHttp() throws Exception {
    String first = addTask(1, "Create User");
    String second = addTask(2, "Configure Security");

    postJson(
            "/api/projects/" + project + "/tasks/" + second + "/dependencies",
            "{\"dependsOnTaskId\":\"" + first + "\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PLANNED"))
        .andExpect(jsonPath("$.dependsOn[0]").value(first));

    mvc.perform(get("/api/projects/{id}/state", project))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalTasks").value(2))
        .andExpect(jsonPath("$.completedTasks").value(0))
        .andExpect(jsonPath("$.progressPercentage").value(0))
        .andExpect(jsonPath("$.nextCandidateTasks[0].title").value("Create User"))
        .andExpect(jsonPath("$.nextCandidateTasks.length()").value(1));
  }

  @Test
  @DisplayName("a self-dependency is refused with a domain-rule error, not a 500")
  void selfDependencyIsRefused() throws Exception {
    String task = addTask(1, "Solo");

    postJson(
            "/api/projects/" + project + "/tasks/" + task + "/dependencies",
            "{\"dependsOnTaskId\":\"" + task + "\"}")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DOMAIN_RULE_VIOLATION"));
  }

  @Test
  @DisplayName("recording evidence returns the verdict and what is still missing")
  void evidenceOverHttp() throws Exception {
    String task = addTask(1, "Create User");
    postJson(
            "/api/projects/" + project + "/tasks/" + task + "/criteria",
            "{\"description\":\"Uma pessoa revisou o schema\",\"required\":true}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"));

    postJson(
            "/api/projects/" + project + "/tasks/" + task + "/evidence",
            """
            {"type":"BUILD_RESULT",
             "rawContent":"Tudo concluído com sucesso!\\nTests run: 8, Failures: 2, Errors: 0",
             "source":"maven"}
            """)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.analysis.status").value("FAILURE"))
        .andExpect(jsonPath("$.analysis.shouldContinue").value(false))
        .andExpect(jsonPath("$.taskCompleted").value(false))
        .andExpect(jsonPath("$.taskStatus").value("IN_PROGRESS"));

    mvc.perform(get("/api/projects/{p}/tasks/{t}/evidence", project, task))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].analysis.signals[0]").value("TESTS_FAILED"));
  }

  @Test
  @DisplayName("next-step and guide answer without any model")
  void guidanceOverHttp() throws Exception {
    String task = addTask(1, "Create User");
    postJson(
        "/api/projects/" + project + "/tasks/" + task + "/evidence",
        """
        {"type":"BUILD_RESULT","rawContent":"BUILD FAILURE\\nCompilation error","source":"maven"}
        """);

    mvc.perform(get("/api/projects/{id}/next-step", project))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("FIX_ERROR"))
        .andExpect(jsonPath("$.priority").value("CRITICAL"))
        .andExpect(jsonPath("$.taskId").value(task))
        .andExpect(jsonPath("$.reason").isNotEmpty())
        .andExpect(jsonPath("$.suggestedPromptType").value("FIX_ERROR"))
        .andExpect(jsonPath("$.requiredActions").isNotEmpty());

    mvc.perform(get("/api/projects/{id}/guide", project))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.whereYouAre").value(org.hamcrest.Matchers.containsString("Authentication")))
        .andExpect(jsonPath("$.recommendedNextStep.type").value("FIX_ERROR"))
        .andExpect(jsonPath("$.progressPercentage").value(0));
  }

  @Test
  @DisplayName("the generated prompt comes back as text, with its sources listed")
  void promptOverHttp() throws Exception {
    String task = addTask(1, "Create User");
    postJson(
        "/api/projects/" + project + "/tasks/" + task + "/evidence",
        """
        {"type":"BUILD_RESULT","rawContent":"BUILD FAILURE\\nCompilation error in User.java",
         "source":"maven"}
        """);

    postJson("/api/projects/" + project + "/prompts/generate", "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("FIX_ERROR"))
        .andExpect(jsonPath("$.taskTitle").value("Create User"))
        .andExpect(
            jsonPath("$.content")
                .value(org.hamcrest.Matchers.containsString("Compilation error in User.java")))
        .andExpect(jsonPath("$.contextSources").isNotEmpty());
  }

  @Test
  @DisplayName("a criterion decision must name who made it")
  void criterionDecisionRequiresAName() throws Exception {
    String task = addTask(1, "Create User");
    String criterion =
        idOf(
            postJson(
                "/api/projects/" + project + "/tasks/" + task + "/criteria",
                "{\"description\":\"Revisado\",\"required\":true}"));

    mvc.perform(
            patch(
                    "/api/projects/{p}/tasks/{t}/criteria/{c}",
                    project,
                    task,
                    criterion)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SATISFIED\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    mvc.perform(
            patch("/api/projects/{p}/tasks/{t}/criteria/{c}", project, task, criterion)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SATISFIED\",\"decidedBy\":\"louvens\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SATISFIED"))
        .andExpect(jsonPath("$.decidedBy").value("louvens"));
  }

  @Test
  @DisplayName("events raise memory proposals, and nothing reaches the Brain without acceptance")
  void memoryProposalsOverHttp() throws Exception {
    String task = addTask(1, "Create User");
    postJson(
        "/api/projects/" + project + "/tasks/" + task + "/evidence",
        """
        {"type":"BUILD_RESULT","rawContent":"BUILD FAILURE","source":"maven"}
        """);

    // The Brain is still empty: the event only produced a proposal.
    mvc.perform(get("/api/projects/{id}/brain", project))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entryCount").value(0));

    String listed =
        mvc.perform(get("/api/projects/{id}/brain/proposals?pendingOnly=true", project))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].trigger").value("ERROR_FOUND"))
            .andExpect(jsonPath("$[0].status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String proposal = json.readTree(listed).get(0).get("id").asText();

    mvc.perform(post("/api/projects/{p}/brain/proposals/{id}/accept", project, proposal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("ERROR"));

    mvc.perform(get("/api/projects/{id}/brain", project))
        .andExpect(jsonPath("$.entryCount").value(1));
  }

  @Test
  @DisplayName("the stateless analyze endpoint still works and stores nothing")
  void analyzeEndpointIsUnchanged() throws Exception {
    postJson(
            "/api/projects/" + project + "/outputs/analyze",
            """
            {"content":"Tudo concluído com sucesso!\\nTests run: 8, Failures: 2, Errors: 0"}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILURE"))
        .andExpect(jsonPath("$.shouldContinue").value(false));

    mvc.perform(get("/api/projects/{id}/evidence", project))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("a task from another project is refused")
  void crossProjectAccessIsRefused() throws Exception {
    String task = addTask(1, "Create User");
    String otherProject = ProjectTestSupport.createProject(mvc, "Outro", "Outro projeto");

    mvc.perform(get("/api/projects/{p}/tasks/{t}", otherProject, task))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DOMAIN_RULE_VIOLATION"));

    mvc.perform(get("/api/projects/{p}/tasks/{t}", project, UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }
}
