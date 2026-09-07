package com.vibecode.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.domain.User;
import com.vibecode.project.web.ProjectTestSupport;
import com.vibecode.support.TestIdentity;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Insecure Direct Object Reference: Bob knows every id in Alice's project and still gets nothing.
 *
 * <p>Guessing a UUID is the whole attack. Every one of these requests is well-formed, authenticated
 * and points at a resource that genuinely exists — the only thing wrong with it is who is asking.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectIsolationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TestIdentity identity;

  private User alice;
  private User bob;

  /** Ids from Alice's project. Bob is given all of them. */
  private String project;
  private String phase;
  private String task;
  private String criterion;
  private String proposal;

  @BeforeEach
  void setUp() throws Exception {
    if (project != null) {
      return;
    }
    alice = identity.createUser("Alice");
    bob = identity.createUser("Bob");

    project = ProjectTestSupport.createProject(mvc, alice, "Alice's project", "Segredo da Alice");
    postAs(alice, "/api/projects/" + project + "/roadmap", null);
    phase =
        idOf(
            postAs(
                alice,
                "/api/projects/" + project + "/roadmap/phases",
                "{\"position\":1,\"title\":\"Fase da Alice\"}"));
    task =
        idOf(
            postAs(
                alice,
                "/api/projects/" + project + "/roadmap/phases/" + phase + "/tasks",
                """
                {"position":1,"title":"Tarefa da Alice","objective":"Objetivo privado",
                 "riskLevel":"LOW"}
                """));
    criterion =
        idOf(
            postAs(
                alice,
                "/api/projects/" + project + "/tasks/" + task + "/criteria",
                "{\"description\":\"Critério da Alice\",\"required\":true}"));
    postAs(
        alice,
        "/api/projects/" + project + "/tasks/" + task + "/evidence",
        """
        {"type":"BUILD_RESULT","rawContent":"BUILD FAILURE segredo","source":"maven"}
        """);
    postAs(
        alice,
        "/api/projects/" + project + "/brain/entries",
        """
        {"type":"VISION","title":"Visão secreta","content":"Só a Alice vê","source":"alice"}
        """);
    String proposals =
        mvc.perform(
                get("/api/projects/{id}/brain/proposals", project).with(TestIdentity.as(alice)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    proposal = json.readTree(proposals).get(0).get("id").asText();
  }

  private String postAs(User user, String url, String body) throws Exception {
    MockHttpServletRequestBuilder request =
        post(url).with(TestIdentity.as(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON);
    if (body != null) {
      request = request.content(body);
    }
    return mvc.perform(request).andReturn().getResponse().getContentAsString();
  }

  private String idOf(String body) throws Exception {
    return json.readTree(body).get("id").asText();
  }

  /** Every read Bob could attempt against Alice's project. */
  private Stream<MockHttpServletRequestBuilder> bobReads() {
    return Stream.of(
            "/api/projects/" + project,
            "/api/projects/" + project + "/brain",
            "/api/projects/" + project + "/brain/proposals",
            "/api/projects/" + project + "/roadmap",
            "/api/projects/" + project + "/state",
            "/api/projects/" + project + "/guide",
            "/api/projects/" + project + "/next-step",
            "/api/projects/" + project + "/tasks",
            "/api/projects/" + project + "/tasks/" + task,
            "/api/projects/" + project + "/tasks/" + task + "/evidence",
            "/api/projects/" + project + "/evidence")
        .map(url -> get(url).with(TestIdentity.as(bob)));
  }

  /** Every write Bob could attempt. */
  private Stream<MockHttpServletRequestBuilder> bobWrites() {
    return Stream.of(
        post("/api/projects/" + project + "/roadmap"),
        post("/api/projects/" + project + "/roadmap/phases")
            .content("{\"position\":9,\"title\":\"Invasão\"}"),
        post("/api/projects/" + project + "/roadmap/phases/" + phase + "/tasks")
            .content(
                """
                {"position":9,"title":"Tarefa do Bob","objective":"Invadir","riskLevel":"LOW"}
                """),
        post("/api/projects/" + project + "/tasks/" + task + "/criteria")
            .content("{\"description\":\"Critério do Bob\",\"required\":false}"),
        post("/api/projects/" + project + "/tasks/" + task + "/evidence")
            .content(
                """
                {"type":"BUILD_RESULT","rawContent":"BUILD SUCCESS","source":"bob"}
                """),
        post("/api/projects/" + project + "/tasks/" + task + "/start"),
        post("/api/projects/" + project + "/brain/entries")
            .content(
                """
                {"type":"NOTE","title":"Bob esteve aqui","content":"x","source":"bob"}
                """),
        post("/api/projects/" + project + "/brain/proposals/" + proposal + "/accept"),
        post("/api/projects/" + project + "/brain/proposals/" + proposal + "/reject"),
        post("/api/projects/" + project + "/outputs/analyze")
            .content("{\"content\":\"BUILD SUCCESS\"}"),
        post("/api/projects/" + project + "/prompts/generate").content("{}"),
        patch("/api/projects/" + project + "/tasks/" + task + "/criteria/" + criterion)
            .content("{\"status\":\"SATISFIED\",\"decidedBy\":\"bob\"}"));
  }

  @Test
  @DisplayName("Bob cannot read anything of Alice's, and learns nothing from the response")
  void bobCannotReadAlicesProject() throws Exception {
    List<MockHttpServletRequestBuilder> reads = bobReads().toList();
    assertThat(reads).hasSize(11);

    for (MockHttpServletRequestBuilder read : reads) {
      String body =
          mvc.perform(read)
              .andExpect(status().isNotFound())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body)
          .doesNotContain("Segredo da Alice")
          .doesNotContain("Visão secreta")
          .doesNotContain("Tarefa da Alice")
          .doesNotContain("Critério da Alice")
          .doesNotContain("BUILD FAILURE segredo");
    }
  }

  @Test
  @DisplayName("Bob cannot write anything into Alice's project")
  void bobCannotWriteIntoAlicesProject() throws Exception {
    List<MockHttpServletRequestBuilder> writes = bobWrites().toList();
    assertThat(writes).hasSize(12);

    for (MockHttpServletRequestBuilder write : writes) {
      mvc.perform(
              write
                  .with(TestIdentity.as(bob))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isNotFound());
    }
  }

  @Test
  @DisplayName("Alice's project is untouched after everything Bob tried")
  void alicesProjectSurvivesIntact() throws Exception {
    bobWrites()
        .forEach(
            write -> {
              try {
                mvc.perform(
                    write
                        .with(TestIdentity.as(bob))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON));
              } catch (Exception ignored) {
                // The assertion below is what matters: nothing changed.
              }
            });

    mvc.perform(get("/api/projects/{id}/roadmap", project).with(TestIdentity.as(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalPhases").value(1))
        .andExpect(jsonPath("$.phases[0].title").value("Fase da Alice"))
        .andExpect(jsonPath("$.phases[0].totalTasks").value(1));

    mvc.perform(get("/api/projects/{id}/brain", project).with(TestIdentity.as(alice)))
        .andExpect(jsonPath("$.entryCount").value(1));

    mvc.perform(
            get("/api/projects/{p}/tasks/{t}/evidence", project, task)
                .with(TestIdentity.as(alice)))
        .andExpect(jsonPath("$.length()").value(1));

    mvc.perform(
            get("/api/projects/{p}/tasks/{t}", project, task).with(TestIdentity.as(alice)))
        .andExpect(jsonPath("$.acceptanceCriteria.length()").value(1))
        .andExpect(jsonPath("$.acceptanceCriteria[0].status").value("PENDING"));
  }

  @Test
  @DisplayName("the project list is scoped by owner, not filtered in the browser")
  void listingOnlyReturnsOwnProjects() throws Exception {
    ProjectTestSupport.createProject(mvc, bob, "Bob's own", "Ideia do Bob");

    mvc.perform(get("/api/projects").with(TestIdentity.as(bob)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name == \"Alice's project\")]").isEmpty())
        .andExpect(jsonPath("$[?(@.name == 'Bob\\'s own')]").isNotEmpty());

    mvc.perform(get("/api/projects").with(TestIdentity.as(alice)))
        .andExpect(jsonPath("$[?(@.name == 'Bob\\'s own')]").isEmpty());
  }

  @Test
  @DisplayName("an unauthenticated caller gets nothing at all")
  void anonymousAccessIsRefused() throws Exception {
    for (String url :
        List.of(
            "/api/projects",
            "/api/projects/" + project,
            "/api/projects/" + project + "/brain",
            "/api/projects/" + project + "/state",
            "/api/projects/" + project + "/next-step")) {
      mvc.perform(get(url))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
  }

  @Test
  @DisplayName("a project id that does not exist looks exactly like one Bob may not see")
  void unknownAndForbiddenAreIndistinguishable() throws Exception {
    String forbidden =
        mvc.perform(get("/api/projects/{id}", project).with(TestIdentity.as(bob)))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String unknown =
        mvc.perform(get("/api/projects/{id}", UUID.randomUUID()).with(TestIdentity.as(bob)))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(json.readTree(forbidden).get("code").asText())
        .isEqualTo(json.readTree(unknown).get("code").asText());
  }
}
