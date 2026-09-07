package com.vibecode.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.domain.User;
import com.vibecode.support.TestIdentity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The invariant this whole phase rests on: a raw secret is redacted <em>before</em> anything is
 * written, never cleaned up afterwards.
 *
 * <p>Rather than checking a handful of tables by name, this sweeps every text column of every table
 * in the schema. A table added later is covered automatically — which matters, because the failure
 * mode here is someone storing the secret somewhere nobody thought to look.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RawSecretPersistenceTest {

  /** Synthetic. Distinctive enough that a substring match cannot be a coincidence. */
  private static final String SYNTHETIC_SECRET = "vc_test_secret_847291";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired TestIdentity identity;

  private User alice;
  private String projectId;
  private String taskId;

  private MockHttpServletRequestBuilder postAs(String url) {
    return post(url).with(TestIdentity.as(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON);
  }

  private String idOf(String body) throws Exception {
    return json.readTree(body).get("id").asText();
  }

  @BeforeEach
  void setUp() throws Exception {
    alice = identity.createUser("secret-owner");

    projectId =
        idOf(
            mvc.perform(
                    postAs("/api/projects")
                        .content(
                            """
                            {"name":"Leak check","originalIdea":"Provar que segredo não persiste"}
                            """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

    mvc.perform(postAs("/api/projects/" + projectId + "/roadmap")).andExpect(status().isCreated());
    String phaseId =
        idOf(
            mvc.perform(
                    postAs("/api/projects/" + projectId + "/roadmap/phases")
                        .content("{\"position\":1,\"title\":\"Setup\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    taskId =
        idOf(
            mvc.perform(
                    postAs("/api/projects/" + projectId + "/roadmap/phases/" + phaseId + "/tasks")
                        .content(
                            """
                            {"position":1,"title":"Configurar CI","objective":"Pipeline",
                             "riskLevel":"HIGH"}
                            """))
                .andReturn()
                .getResponse()
                .getContentAsString());
  }

  @Test
  @DisplayName("a secret pasted as evidence reaches no column of any table")
  void secretFromEvidenceIsNeverPersisted() throws Exception {
    mvc.perform(
            postAs("/api/projects/" + projectId + "/tasks/" + taskId + "/evidence")
                .content(
                    """
                    {"type":"BUILD_RESULT",
                     "rawContent":"BUILD SUCCESS\\nCLIENT_SECRET=%s\\nTests run: 4, Failures: 0",
                     "source":"ci"}
                    """
                        .formatted(SYNTHETIC_SECRET)))
        .andExpect(status().isCreated());

    assertNoColumnContainsTheSecret();
  }

  @Test
  @DisplayName("a secret sent to the inspect endpoint reaches no column of any table")
  void secretFromInspectionIsNeverPersisted() throws Exception {
    mvc.perform(
            postAs("/api/projects/" + projectId + "/security/inspect")
                .content(
                    """
                    {"sourceType":"GENERIC_TEXT","sourceId":"leak-check",
                     "content":"export CLIENT_SECRET=%s"}
                    """
                        .formatted(SYNTHETIC_SECRET)))
        .andExpect(status().isOk());

    assertNoColumnContainsTheSecret();
  }

  @Test
  @DisplayName("the secret is absent from every API response as well, not only from the database")
  void secretIsAbsentFromApiResponses() throws Exception {
    mvc.perform(
            postAs("/api/projects/" + projectId + "/tasks/" + taskId + "/evidence")
                .content(
                    """
                    {"type":"BUILD_RESULT","rawContent":"CLIENT_SECRET=%s","source":"ci"}
                    """
                        .formatted(SYNTHETIC_SECRET)))
        .andExpect(status().isCreated());

    List<String> urls =
        List.of(
            "/api/projects/" + projectId + "/security",
            "/api/projects/" + projectId + "/security/findings",
            "/api/projects/" + projectId + "/audit",
            "/api/projects/" + projectId + "/tasks/" + taskId + "/evidence",
            "/api/projects/" + projectId + "/state",
            "/api/projects/" + projectId + "/guide",
            "/api/projects/" + projectId + "/next-step");

    for (String url : urls) {
      String body =
          mvc.perform(get(url).with(TestIdentity.as(alice)))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body).as("resposta de %s", url).doesNotContain(SYNTHETIC_SECRET);
    }

    String prompt =
        mvc.perform(postAs("/api/projects/" + projectId + "/prompts/generate").content("{}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(prompt).doesNotContain(SYNTHETIC_SECRET);
  }

  @Test
  @DisplayName("negative control: the sweep does see stored data, so a pass is not vacuous")
  void theSweepActuallyReadsStoredData() throws Exception {
    mvc.perform(
            postAs("/api/projects/" + projectId + "/tasks/" + taskId + "/evidence")
                .content(
                    """
                    {"type":"BUILD_RESULT","rawContent":"CLIENT_SECRET=%s","source":"ci"}
                    """
                        .formatted(SYNTHETIC_SECRET)))
        .andExpect(status().isCreated());

    // The redacted form must be there: it proves the evidence was stored and that a LIKE over
    // these columns can find content. Without this, a passing sweep could just mean it read
    // nothing at all.
    Integer redactedRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM task_evidence WHERE raw_content LIKE ?",
            Integer.class,
            "%[REDACTED]%");
    assertThat(redactedRows).as("a evidência redigida deveria estar no banco").isPositive();

    Integer rawRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM task_evidence WHERE raw_content LIKE ?",
            Integer.class,
            "%" + SYNTHETIC_SECRET + "%");
    assertThat(rawRows).as("o segredo bruto não pode estar no banco").isZero();
  }

  /** Sweeps every character-typed column of every table in the schema. */
  private void assertNoColumnContainsTheSecret() {
    List<String> offenders = new ArrayList<>();

    // Every column of every table, whatever its declared type: casting to text means a secret
    // cannot hide in a column whose type nobody expected to hold one.
    List<TextColumn> columns =
        jdbc.query(
            """
            SELECT table_name, column_name
            FROM information_schema.columns
            WHERE UPPER(table_schema) = 'PUBLIC'
              AND UPPER(table_name) <> 'FLYWAY_SCHEMA_HISTORY'
            ORDER BY table_name, column_name
            """,
            (rs, rowNum) -> new TextColumn(rs.getString(1), rs.getString(2)));

    assertThat(columns).as("o schema precisa ter colunas para varrer").isNotEmpty();

    int scanned = 0;
    for (TextColumn column : columns) {
      try {
        Integer hits =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"%s\" WHERE CAST(\"%s\" AS VARCHAR) LIKE ?"
                    .formatted(column.table(), column.name()),
                Integer.class,
                "%" + SYNTHETIC_SECRET + "%");
        scanned++;
        if (hits != null && hits > 0) {
          offenders.add(column.table() + "." + column.name() + " (" + hits + " linha(s))");
        }
      } catch (org.springframework.dao.DataAccessException notCastable) {
        // A column whose type has no text form cannot hold the secret as a substring.
      }
    }
    assertThat(scanned).as("nenhuma coluna foi efetivamente varrida").isGreaterThan(20);

    assertThat(offenders)
        .as("o segredo bruto foi persistido em %s", offenders)
        .isEmpty();
  }

  private record TextColumn(String table, String name) {}
}
