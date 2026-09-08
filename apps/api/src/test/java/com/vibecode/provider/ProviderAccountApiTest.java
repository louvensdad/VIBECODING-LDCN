package com.vibecode.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.domain.User;
import com.vibecode.support.TestIdentity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP boundary.
 *
 * <p>These are the tests that matter most in this module, because the boundary is where a leak
 * would actually reach someone. Runs uncommitted-transaction-free so audit rows, which are written
 * in their own transaction, see the same data a real request would.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderAccountApiTest {

  // The last four characters carry weight: the sweeps below forbid them everywhere, because a
  // masked key shows its suffix and the suffix is the piece that confirms to an attacker which key
  // they are holding. They are deliberately outside the hexadecimal alphabet. A numeric suffix is a
  // four-character needle, and the audit trail is full of UUIDs — one of them contains any given
  // four hex digits often enough that this test went red on a coincidence rather than a leak,
  // measured at roughly one full-suite run in fifty. "zqxw" cannot occur inside a UUID, a timestamp
  // or a generated identifier, so a hit is a leak and nothing else.
  private static final String CREDENTIAL = "vc_anthropic_test_secret_92zqxw";
  private static final String ROTATED = "vc_anthropic_test_secret_92zqxy";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TestIdentity testIdentity;
  @Autowired DataSource dataSource;

  private User alice;
  private User bob;
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    alice = testIdentity.createUser("alice");
    bob = testIdentity.createUser("bob");
    jdbc = new JdbcTemplate(dataSource);
  }

  private UUID createAccount(User owner) throws Exception {
    String body =
        """
        {"provider":"ANTHROPIC","displayName":"My Claude key","authenticationType":"API_KEY"}
        """;
    String response =
        mvc.perform(
                post("/api/provider-accounts")
                    .with(TestIdentity.as(owner))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private String storeCredential(User owner, UUID accountId, String credential) throws Exception {
    return mvc.perform(
            put("/api/provider-accounts/" + accountId + "/credential")
                .with(TestIdentity.as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"" + credential + "\"}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @DisplayName("A new account starts with no credential and says so honestly")
  void newAccountHasNoCredential() throws Exception {
    UUID id = createAccount(alice);

    mvc.perform(get("/api/provider-accounts/" + id).with(TestIdentity.as(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING_CREDENTIAL"))
        .andExpect(jsonPath("$.hasCredential").value(false))
        .andExpect(jsonPath("$.credentialUpdatedAt").doesNotExist());
  }

  @Test
  @DisplayName("Storing a credential reports it stored and unverified, never connected")
  void statusIsHonestAboutVerification() throws Exception {
    UUID id = createAccount(alice);

    String response = storeCredential(alice, id, CREDENTIAL);

    // Nothing was sent to Anthropic, so nothing is known about whether the key works. A status of
    // CONNECTED or VALID would be the application asserting something it never checked.
    assertThat(json.readTree(response).get("status").asText())
        .isEqualTo("CREDENTIAL_STORED_UNVERIFIED");
    assertThat(json.readTree(response).get("hasCredential").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("No response anywhere contains the credential, a prefix of it, or a suffix of it")
  void noResponseLeaksTheCredential() throws Exception {
    UUID id = createAccount(alice);

    List<String> responses =
        List.of(
            storeCredential(alice, id, CREDENTIAL),
            body(get("/api/provider-accounts").with(TestIdentity.as(alice))),
            body(get("/api/provider-accounts/" + id).with(TestIdentity.as(alice))),
            body(get("/api/provider-accounts/catalog").with(TestIdentity.as(alice))));

    for (String response : responses) {
      assertThat(response).doesNotContain(CREDENTIAL);
      // Not the first characters, and not the last four either. A masked key is still a piece of
      // a key, and it is the piece that confirms to an attacker which key they are holding.
      assertThat(response).doesNotContain(CREDENTIAL.substring(0, 8));
      assertThat(response).doesNotContain(CREDENTIAL.substring(CREDENTIAL.length() - 4));
      // And no field that would exist only to carry one.
      assertThat(response)
          .doesNotContain("apiKey")
          .doesNotContain("secretValue")
          .doesNotContain("plaintext")
          .doesNotContain("credential\":")
          .doesNotContain("maskedKey")
          .doesNotContain("lastFour")
          .doesNotContain("fingerprint");
    }
  }

  @Test
  @DisplayName("There is no endpoint that returns credential material")
  void noEndpointReturnsMaterial() throws Exception {
    UUID id = createAccount(alice);
    storeCredential(alice, id, CREDENTIAL);

    // These are the routes it would be natural for someone to add later. Each must stay absent.
    for (String path :
        List.of(
            "/api/provider-accounts/" + id + "/credential",
            "/api/provider-accounts/" + id + "/api-key",
            "/api/provider-accounts/" + id + "/credential/plaintext",
            "/api/secrets/" + id + "/plaintext")) {
      int statusCode =
          mvc.perform(get(path).with(TestIdentity.as(alice)))
              .andReturn()
              .getResponse()
              .getStatus();
      assertThat(statusCode)
          .describedAs("GET %s must not be a readable endpoint", path)
          .isNotEqualTo(200);
    }
  }

  @Test
  @DisplayName("The credential reaches the database only as ciphertext")
  void databaseStoresOnlyCiphertext() throws Exception {
    UUID id = createAccount(alice);
    storeCredential(alice, id, CREDENTIAL);

    List<byte[]> ciphertexts =
        jdbc.query("SELECT ciphertext FROM vault_secret_versions", (rs, row) -> rs.getBytes(1));
    assertThat(ciphertexts).isNotEmpty();
    for (byte[] ciphertext : ciphertexts) {
      assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain(CREDENTIAL);
    }

    // The account row points at the vault and holds nothing else.
    String accountRow =
        jdbc.queryForObject(
            "SELECT display_name || CAST(status AS VARCHAR) || CAST(provider AS VARCHAR) "
                + "FROM provider_accounts WHERE id = ?",
            String.class,
            id);
    assertThat(accountRow).doesNotContain(CREDENTIAL);
  }

  @Test
  @DisplayName("The audit trail records that a credential was stored, and nothing about its value")
  void auditRecordsTheEventNotTheSecret() throws Exception {
    UUID id = createAccount(alice);
    storeCredential(alice, id, CREDENTIAL);

    List<String> rows =
        jdbc.queryForList(
            "SELECT COALESCE(CAST(event_type AS VARCHAR), '') || COALESCE(target_type, '') "
                + "|| COALESCE(target_id, '') || COALESCE(result, '') || COALESCE(metadata, '') "
                + "FROM audit_events",
            String.class);

    assertThat(rows).anyMatch(row -> row.contains("PROVIDER_CREDENTIAL_STORED"));
    assertThat(rows).anyMatch(row -> row.contains("PROVIDER_ACCOUNT_CREATED"));

    // Every row in the table, not only the ones this test wrote: the class commits, so the sweep
    // covers whatever else the suite recorded, and a credential fragment surfacing in someone
    // else's audit row is exactly as bad as it surfacing here. It is also the only thing covering
    // the vault tests' credential, which shares this literal for that reason — moving it here
    // without moving it there would quietly retire that cover.
    //
    // The breadth is affordable only while the forbidden suffix cannot turn up by coincidence, so
    // the fixture's shape is pinned rather than left to a comment. This rules out one specific
    // hazard and not collisions in general: a fully hex-legal suffix, which the UUIDs filling every
    // target_id in this table would eventually contain. A suffix like "tion" or "wner" would pass
    // this guard and still collide happily with the event and target names alongside them.
    assertThat(CREDENTIAL.substring(CREDENTIAL.length() - 4))
        .as("a hex-legal suffix would collide with the UUIDs in this table")
        .doesNotMatch("[0-9a-fA-F]{4}");

    for (String row : rows) {
      assertThat(row).doesNotContain(CREDENTIAL);
      assertThat(row).doesNotContain(CREDENTIAL.substring(0, 8));
      assertThat(row).doesNotContain(CREDENTIAL.substring(CREDENTIAL.length() - 4));
    }
  }

  @Test
  @DisplayName("Rotation replaces the material and records the event")
  void rotationReplacesMaterial() throws Exception {
    UUID id = createAccount(alice);
    storeCredential(alice, id, CREDENTIAL);

    String response =
        mvc.perform(
                post("/api/provider-accounts/" + id + "/credential/rotate")
                    .with(TestIdentity.as(alice))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"credential\":\"" + ROTATED + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasCredential").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(response).doesNotContain(ROTATED).doesNotContain(CREDENTIAL);

    // Two versions now exist, and the secret points at the newer of them. Rotation adds; it does
    // not overwrite, so the moment of the switch never leaves the account without a credential.
    // Scoped to this account: the class commits its rows, so other tests' secrets are in the
    // table too.
    Integer versions =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM vault_secret_versions v "
                + "JOIN provider_accounts a ON a.credential_secret_id = v.secret_id "
                + "WHERE a.id = ?",
            Integer.class,
            id);
    assertThat(versions).isEqualTo(2);

    Integer activeVersionNumber =
        jdbc.queryForObject(
            "SELECT v.version_number FROM vault_secret_versions v "
                + "JOIN vault_secrets s ON s.active_version_id = v.id "
                + "JOIN provider_accounts a ON a.credential_secret_id = s.id "
                + "WHERE a.id = ?",
            Integer.class,
            id);
    assertThat(activeVersionNumber).isEqualTo(2);
  }

  @Test
  @DisplayName("Removing a credential leaves the account and drops back to pending")
  void removalKeepsTheAccount() throws Exception {
    UUID id = createAccount(alice);
    storeCredential(alice, id, CREDENTIAL);

    mvc.perform(
            delete("/api/provider-accounts/" + id + "/credential")
                .with(TestIdentity.as(alice))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasCredential").value(false))
        .andExpect(jsonPath("$.status").value("PENDING_CREDENTIAL"));
  }

  @Test
  @DisplayName("A disabled account stays disabled after a credential is stored")
  void disabledAccountStaysDisabled() throws Exception {
    UUID id = createAccount(alice);

    mvc.perform(
            post("/api/provider-accounts/" + id + "/disable")
                .with(TestIdentity.as(alice))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISABLED"));

    // Storing a credential must not quietly re-enable a connection its owner turned off.
    String response = storeCredential(alice, id, CREDENTIAL);
    assertThat(json.readTree(response).get("status").asText()).isEqualTo("DISABLED");
  }

  @Test
  @DisplayName("Another user's account is not found, not forbidden")
  void ownershipIsEnforcedAsNotFound() throws Exception {
    UUID aliceAccount = createAccount(alice);
    storeCredential(alice, aliceAccount, CREDENTIAL);

    mvc.perform(get("/api/provider-accounts/" + aliceAccount).with(TestIdentity.as(bob)))
        .andExpect(status().isNotFound());

    mvc.perform(
            put("/api/provider-accounts/" + aliceAccount + "/credential")
                .with(TestIdentity.as(bob))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"vc_secret_test_928475_example\"}"))
        .andExpect(status().isNotFound());

    mvc.perform(
            delete("/api/provider-accounts/" + aliceAccount + "/credential")
                .with(TestIdentity.as(bob))
                .with(csrf()))
        .andExpect(status().isNotFound());

    // Bob's own listing shows nothing of Alice's.
    JsonNode bobList =
        json.readTree(body(get("/api/provider-accounts").with(TestIdentity.as(bob))));
    assertThat(bobList).isEmpty();
  }

  @Test
  @DisplayName("Credential writes require a CSRF token and a session")
  void writesRequireCsrfAndAuthentication() throws Exception {
    UUID id = createAccount(alice);

    // Authenticated but no token: a form on another site must not be able to replace a credential.
    mvc.perform(
            put("/api/provider-accounts/" + id + "/credential")
                .with(TestIdentity.as(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"" + CREDENTIAL + "\"}"))
        .andExpect(status().isForbidden());

    // Anonymous, token or not.
    mvc.perform(get("/api/provider-accounts")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/provider-accounts/catalog")).andExpect(status().isUnauthorized());
    mvc.perform(
            put("/api/provider-accounts/" + id + "/credential")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"" + CREDENTIAL + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("The catalogue lists providers and no credentials")
  void catalogueListsProviders() throws Exception {
    String response = body(get("/api/provider-accounts/catalog").with(TestIdentity.as(alice)));

    assertThat(response).contains("ANTHROPIC").contains("OPENAI").contains("API_KEY");
    // Only API_KEY is implemented, so nothing else is offered as if it worked.
    assertThat(response).doesNotContain("OAUTH").doesNotContain("SERVICE_ACCOUNT");
  }

  private String body(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse().getContentAsString();
  }
}
