package com.vibecode.context.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.brain.application.BrainService;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.context.domain.ContextPack;
import com.vibecode.identity.domain.User;
import com.vibecode.project.application.ProjectService;
import com.vibecode.project.domain.Project;
import com.vibecode.support.TestIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP boundary of the Context API.
 *
 * <p>Every surface is exercised twice: once with input the route accepts and once with input it
 * must refuse. That is not thoroughness for its own sake. An earlier piece of work on this codebase
 * passed three reviews because every probe sent a valid request, and the defect lived entirely on
 * the path where input is rejected — so a success-only suite here would be evidence of the same
 * shape and worth the same amount.
 *
 * <p>The fixtures are synthetic. The one secret-shaped string below is a literal that matches the
 * redactor's key/value pattern and is not, and never was, a credential.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContextPackApiTest {

  /**
   * A value the Guardian's redactor rewrites. The suffix is outside the hexadecimal alphabet on
   * purpose: a numeric needle collides with the digits of a UUID often enough that a leak assertion
   * would fail on a coincidence rather than on a leak.
   *
   * <p>The key is one the redactor's key/value pattern actually names. {@code API_TOKEN=} was the
   * first fixture tried and passed through untouched — the pattern anchors {@code TOKEN} on a word
   * boundary, and there is none between {@code API_} and {@code TOKEN}. That is a property of the
   * Guardian's redactor and not of this API, so it is recorded here rather than worked around
   * silently; this test is about what the API emits, and it needs a value the redactor is known to
   * catch in order to be about anything at all.
   */
  private static final String SECRET_SHAPED = "ACCESS_TOKEN=vc_fixture_value_92zqxw";

  /**
   * 498 characters on the wire, 507 after redaction — the case that base change 2 is about.
   *
   * <p>Redaction replaces a matched value with {@code [REDACTED]}, which is longer than the value it
   * replaces here, so a reference the DTO's {@code @Size(max = 500)} accepts is over the domain's
   * cap by the time a pack is built. Nothing truncates it: a shortened reference would be a pack
   * claiming to be for a task nobody asked about, and the trimmed form is what would be digested.
   */
  private static final String LENGTHENS_PAST_THE_CAP = "T".repeat(490) + " TOKEN=x";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TestIdentity identity;
  @Autowired ProjectService projects;
  @Autowired BrainService brain;

  private User alice;
  private User bob;
  private UUID aliceProject;
  private UUID aliceOtherProject;
  private UUID bobProject;

  @BeforeEach
  void setUp() {
    alice = identity.createUser("alice");
    bob = identity.createUser("bob");

    identity.authenticateAs(alice);
    aliceProject = createProject("Alice's project");
    aliceOtherProject = createProject("Alice's other project");

    identity.authenticateAs(bob);
    bobProject = createProject("Bob's project");

    identity.clear();
  }

  @AfterEach
  void clearAuthentication() {
    identity.clear();
  }

  private UUID createProject(String name) {
    Project project =
        projects.create(name, "A project that exists so a pack has something to read", "Ship a tool");
    return project.getId();
  }

  private static String compileBody(String taskReference) {
    return "{\"taskReference\":\"" + taskReference + "\"}";
  }

  private MockHttpServletRequestBuilder compileRequest(User caller, UUID projectId, String body) {
    return post("/api/projects/" + projectId + "/context/compile")
        .with(TestIdentity.as(caller))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private JsonNode compile(User caller, UUID projectId, String taskReference) throws Exception {
    String response =
        mvc.perform(compileRequest(caller, projectId, compileBody(taskReference)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response);
  }

  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse().getContentAsString();
  }

  /** The pack ids the list route reports for one project, in the order it reported them. */
  private List<String> listedPackIds(User caller, UUID projectId) throws Exception {
    JsonNode listed =
        json.readTree(body(get("/api/projects/" + projectId + "/context").with(TestIdentity.as(caller))));
    List<String> ids = new ArrayList<>();
    listed.forEach(node -> ids.add(node.get("packId").asText()));
    return ids;
  }

  // ---------------------------------------------------------------- success paths

  @Test
  @DisplayName("Compiling returns 201 and the whole agreed pack shape, item counts included")
  void compileReturnsTheAgreedShape() throws Exception {
    mvc.perform(compileRequest(alice, aliceProject, compileBody("TASK-1: model the domain")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.packId").isNotEmpty())
        .andExpect(jsonPath("$.projectId").value(aliceProject.toString()))
        .andExpect(jsonPath("$.taskReference").value("TASK-1: model the domain"))
        .andExpect(jsonPath("$.assembledAt").isNotEmpty())
        .andExpect(jsonPath("$.budget.maxItems").value(ContextDtos.DEFAULT_BUDGET.maxItems()))
        .andExpect(jsonPath("$.usage.items").isNumber())
        .andExpect(jsonPath("$.usage.characters").isNumber())
        .andExpect(jsonPath("$.usage.bytes").isNumber())
        // The estimate keeps its caveat on the wire. A bare number here would sit beside three
        // measurements and read as a fourth.
        .andExpect(jsonPath("$.usage.estimatedTokenCount.estimatedTokens").isNumber())
        .andExpect(jsonPath("$.usage.estimatedTokenCount.heuristic").isNotEmpty())
        .andExpect(jsonPath("$.usage.estimatedTokenCount.isExact").value(false))
        .andExpect(jsonPath("$.contentFingerprint").isNotEmpty())
        .andExpect(jsonPath("$.items[0].id").isNotEmpty())
        .andExpect(jsonPath("$.items[0].kind").isNotEmpty())
        .andExpect(jsonPath("$.items[0].label").isNotEmpty())
        .andExpect(jsonPath("$.items[0].content").isNotEmpty())
        .andExpect(jsonPath("$.items[0].characterCount").isNumber())
        .andExpect(jsonPath("$.items[0].byteCount").isNumber())
        .andExpect(jsonPath("$.items[0].provenance.source.type").isNotEmpty())
        .andExpect(jsonPath("$.items[0].provenance.source.sourceId").isNotEmpty())
        .andExpect(jsonPath("$.items[0].provenance.projectId").value(aliceProject.toString()))
        .andExpect(jsonPath("$.items[0].provenance.recordedAt").isNotEmpty())
        // Provenance says where from; admission says why. Neither stands in for the other, and an
        // item carrying only one of them is the failure the whole engine exists to prevent.
        .andExpect(jsonPath("$.items[0].admission.policyRuleId").isNotEmpty())
        .andExpect(jsonPath("$.items[0].admission.explanation").isNotEmpty());
  }

  @Test
  @DisplayName("A compiled pack reads back by id with the same selection, to microsecond precision")
  void compiledPackReadsBackWithTheSameSelection() throws Exception {
    JsonNode compiled = compile(alice, aliceProject, "TASK-2: read it back");
    String packId = compiled.get("packId").asText();

    String fetched =
        body(get("/api/projects/" + aliceProject + "/context/" + packId).with(TestIdentity.as(alice)));
    JsonNode reread = json.readTree(fetched);

    // The pack id, the item count and the fingerprint together: a status code alone would be
    // satisfied by any 200, and the fingerprint is what says the stored rows describe the same
    // selection the compile call returned.
    assertThat(reread.get("packId").asText()).isEqualTo(packId);
    assertThat(reread.get("items").size()).isEqualTo(compiled.get("items").size());
    assertThat(reread.get("contentFingerprint").asText())
        .isEqualTo(compiled.get("contentFingerprint").asText());

    // THE ONE FIELD THAT IS NOT IDENTICAL, asserted rather than described. This test was previously
    // named "reads back identically by id" and checked three fields, none of them this one.
    //
    // POST answers from the compiled pack, which carries the clock's instant at nanosecond
    // resolution. GET answers from the stored row, and both engines hold a timestamp to
    // microseconds — so the same pack reports two spellings of one instant.
    //
    // The stored value is ROUNDED, not truncated, and the difference is not pedantry: the first
    // version of this assertion compared the two after truncating both to microseconds, passed in
    // isolation, and failed in the full suite on 615643600ns, which the database stored as
    // 615644us — half a microsecond LATER than the instant the pack was compiled at. So the
    // persisted instant is not merely a shortened form of the compiled one and can round upwards
    // past it.
    //
    // The behaviour is left alone and the claim corrected. Making the two byte-identical would mean
    // re-reading every pack after writing it — a real query on every compile, bought to make a
    // sentence true about half a microsecond. What is asserted instead is the property that
    // actually holds: they agree to within the precision the database keeps.
    java.time.Instant fromCompile = java.time.Instant.parse(compiled.get("assembledAt").asText());
    java.time.Instant fromStorage = java.time.Instant.parse(reread.get("assembledAt").asText());
    assertThat(java.time.Duration.between(fromCompile, fromStorage).abs())
        .isLessThan(java.time.Duration.ofNanos(1_000L));

    // Everything except that field is byte-identical, which is what makes the exception above the
    // whole of the difference rather than the part that happened to be noticed.
    assertThat(withoutAssembledAt(reread)).isEqualTo(withoutAssembledAt(compiled));
  }

  /** The pack with its top-level {@code assembledAt} removed. See the test above for why. */
  private static JsonNode withoutAssembledAt(JsonNode pack) {
    return ((com.fasterxml.jackson.databind.node.ObjectNode) pack.deepCopy())
        .without("assembledAt");
  }

  @Test
  @DisplayName("The list is scoped to one project and holds exactly the packs compiled into it")
  void listIsScopedToTheProject() throws Exception {
    String first = compile(alice, aliceProject, "TASK-3: first").get("packId").asText();
    String second = compile(alice, aliceProject, "TASK-4: second").get("packId").asText();
    compile(alice, aliceOtherProject, "TASK-5: elsewhere");

    assertThat(listedPackIds(alice, aliceProject)).containsExactlyInAnyOrder(first, second);
    assertThat(listedPackIds(alice, aliceOtherProject)).hasSize(1);
  }

  @Test
  @DisplayName("A secret-shaped value in a brain entry reaches the API redacted, never raw")
  void nothingRawReachesTheWire() throws Exception {
    identity.authenticateAs(alice);
    brain.add(
        aliceProject,
        BrainEntryType.DECISION,
        "Configuration decision",
        "The build reads " + SECRET_SHAPED + " from the environment.",
        "test");
    identity.clear();

    JsonNode compiled = compile(alice, aliceProject, "TASK-6: check redaction");
    String packId = compiled.get("packId").asText();

    String[] surfaces = {
      compiled.toString(),
      body(get("/api/projects/" + aliceProject + "/context/" + packId).with(TestIdentity.as(alice))),
      body(get("/api/projects/" + aliceProject + "/context").with(TestIdentity.as(alice)))
    };
    for (String surface : surfaces) {
      assertThat(surface).doesNotContain("vc_fixture_value_92zqxw");
      assertThat(surface).contains("[REDACTED]");
    }
  }

  @Test
  @DisplayName("The emitted JSON carries exactly the field names the agreed contract declares")
  void theWireMatchesTheAgreedContract() throws Exception {
    // The static parity script compares the Java record components against the TypeScript. This is
    // the half the script cannot do: it observes the bytes Jackson actually produced. The two
    // together are what make the wire contract checked rather than reasoned about — the contract
    // file itself says the payload had not been observed when it was written.
    JsonNode pack = compile(alice, aliceProject, "TASK-16: observe the wire");

    assertThat(fieldNames(pack))
        .containsExactlyInAnyOrder(
            "packId",
            "projectId",
            "taskReference",
            "assembledAt",
            "budget",
            "usage",
            "items",
            "contentFingerprint");
    assertThat(fieldNames(pack.get("budget")))
        .containsExactlyInAnyOrder("maxItems", "maxCharacters", "maxBytes");
    assertThat(fieldNames(pack.get("usage")))
        .containsExactlyInAnyOrder("items", "characters", "bytes", "estimatedTokenCount");
    assertThat(fieldNames(pack.get("usage").get("estimatedTokenCount")))
        .containsExactlyInAnyOrder("estimatedTokens", "heuristic", "isExact");

    JsonNode item = pack.get("items").get(0);
    assertThat(fieldNames(item))
        .containsExactlyInAnyOrder(
            "id",
            "kind",
            "label",
            "content",
            "provenance",
            "admission",
            "characterCount",
            "byteCount");
    assertThat(fieldNames(item.get("provenance")))
        .containsExactlyInAnyOrder("source", "projectId", "recordedAt");
    assertThat(fieldNames(item.get("provenance").get("source")))
        .containsExactlyInAnyOrder("type", "sourceId", "version");
    assertThat(fieldNames(item.get("admission")))
        .containsExactlyInAnyOrder("policyRuleId", "explanation");

    // Instants are ISO-8601 strings and not epoch numbers, which is what the contract declares.
    assertThat(pack.get("assembledAt").isTextual()).isTrue();
    assertThat(item.get("provenance").get("recordedAt").isTextual()).isTrue();
  }

  /**
   * Every key one JSON object carries, so a superset is a failure and not merely an unchecked
   * extra. A field nobody agreed to is how content that was never meant to leave gets a route out.
   */
  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  // ---------------------------------------------------------------- failure paths

  @Test
  @DisplayName("Compiling without a CSRF token is refused, and no pack is created")
  void compileRequiresCsrf() throws Exception {
    mvc.perform(
            post("/api/projects/" + aliceProject + "/context/compile")
                .with(TestIdentity.as(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(compileBody("TASK-7: no token")))
        .andExpect(status().isForbidden());

    // The status alone would also be produced by an authorization failure, so the side effect is
    // what says the request was stopped before it did anything.
    assertThat(listedPackIds(alice, aliceProject)).isEmpty();
  }

  @Test
  @DisplayName("Both GETs need no CSRF token, which is the ordinary rule and not an exemption")
  void readsDoNotRequireCsrf() throws Exception {
    String packId = compile(alice, aliceProject, "TASK-17: read without a token").get("packId").asText();

    // Both read routes, not just the list. The two are configured by one rule and it would be odd
    // for them to differ — but "it would be odd" is exactly the reasoning a test is supposed to
    // replace, and only one of them was ever exercised here.
    mvc.perform(get("/api/projects/" + aliceProject + "/context").with(TestIdentity.as(alice)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/projects/" + aliceProject + "/context/" + packId).with(TestIdentity.as(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.packId").value(packId));
  }

  @Test
  @DisplayName("A path variable that is not a UUID is a 400, not a 500 with a stack trace")
  void aMalformedPathVariableIsAClientError() throws Exception {
    // GET on the compile path is the case that matters here: this is the only module with a literal
    // sub-path sitting beside a UUID one, so the most-documented URL in the feature is also the one
    // a person types by hand with the wrong verb. It answered 500 and logged a stack trace.
    String response =
        mvc.perform(get("/api/projects/" + aliceProject + "/context/compile").with(TestIdentity.as(alice)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.violations[0].field").value("packId"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // The body names the parameter and not the value, the target type or the converter.
    assertThat(response).doesNotContain("UUID string");
    assertThat(response).doesNotContain("Exception");
    assertThat(response).doesNotContain("java.");

    mvc.perform(get("/api/projects/not-a-uuid/context").with(TestIdentity.as(alice)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.violations[0].field").value("projectId"));
  }

  @Test
  @DisplayName("Malformed JSON is a 400 about the body, never a 500 and never a parser message")
  void malformedJsonIsAControlledClientError() throws Exception {
    String response =
        mvc.perform(compileRequest(alice, aliceProject, "{\"taskReference\": "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(response).doesNotContain("com.fasterxml");
    assertThat(response).doesNotContain("Exception");
  }

  @Test
  @DisplayName("A missing task reference is a field-level 400, not a compile attempt")
  void missingTaskReferenceIsRejected() throws Exception {
    mvc.perform(compileRequest(alice, aliceProject, "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.violations[0].field").value("taskReference"));
  }

  @Test
  @DisplayName("A 501-character task reference is refused at the boundary")
  void oversizedTaskReferenceIsRejected() throws Exception {
    String tooLong = "T".repeat(ContextPack.MAX_TASK_REFERENCE_LENGTH + 1);

    mvc.perform(compileRequest(alice, aliceProject, compileBody(tooLong)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.violations[0].field").value("taskReference"));
  }

  @Test
  @DisplayName("A reference redaction lengthens past the cap fails as a client error, storing nothing")
  void redactionLengtheningIsAControlledClientError() throws Exception {
    assertThat(LENGTHENS_PAST_THE_CAP).hasSize(498);

    String response =
        mvc.perform(compileRequest(alice, aliceProject, compileBody(LENGTHENS_PAST_THE_CAP)))
            // 422 and not 500: the request was well formed and the value was only over the cap
            // after a step that runs inside the engine. A 500 would report the caller's input as a
            // server fault, and the caller could not act on it.
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_STATE"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // The message names the cap and the measured length, which is what makes the growth legible —
    // a 498-character value refused for exceeding 500 reads as a contradiction without it.
    assertThat(response).contains("may not exceed 500");
    assertThat(response).contains("has 507");
    assertThat(response).doesNotContain("org.hibernate");
    assertThat(response).doesNotContain("SQL");

    // Nothing was written. The assembler's transaction rolled back, so the failed compile left no
    // half-built pack behind for a later reader to mistake for a real one.
    assertThat(listedPackIds(alice, aliceProject)).isEmpty();
  }

  @Test
  @DisplayName("A budget of zero, a negative budget and an absurd one are all refused")
  void invalidBudgetsAreRefused() throws Exception {
    String zero = "{\"taskReference\":\"TASK-8\",\"budget\":{\"maxItems\":0}}";
    String negative = "{\"taskReference\":\"TASK-8\",\"budget\":{\"maxCharacters\":-1}}";
    String absurd = "{\"taskReference\":\"TASK-8\",\"budget\":{\"maxBytes\":9000000000}}";

    for (String requestBody : new String[] {zero, negative, absurd}) {
      mvc.perform(compileRequest(alice, aliceProject, requestBody))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // A number too large for the field's type is a body the request could not carry at all, and is
    // reported as such rather than silently narrowed.
    mvc.perform(
            compileRequest(
                alice, aliceProject, "{\"taskReference\":\"TASK-8\",\"budget\":{\"maxItems\":99999999999}}"))
        .andExpect(status().isBadRequest());

    assertThat(listedPackIds(alice, aliceProject)).isEmpty();
  }

  @Test
  @DisplayName("A named budget is honoured and an omitted dimension falls back to the default")
  void aPartialBudgetResolvesDimensionByDimension() throws Exception {
    String response =
        mvc.perform(
                compileRequest(
                    alice, aliceProject, "{\"taskReference\":\"TASK-9\",\"budget\":{\"maxItems\":7}}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode budget = json.readTree(response).get("budget");
    assertThat(budget.get("maxItems").asInt()).isEqualTo(7);
    assertThat(budget.get("maxCharacters").asLong())
        .isEqualTo(ContextDtos.DEFAULT_BUDGET.maxCharacters());
    assertThat(budget.get("maxBytes").asLong()).isEqualTo(ContextDtos.DEFAULT_BUDGET.maxBytes());
  }

  // ---------------------------------------------------------------- ownership

  @Test
  @DisplayName("A project that does not exist is not found on all three routes")
  void unknownProjectIsNotFound() throws Exception {
    UUID unknown = UUID.randomUUID();

    mvc.perform(compileRequest(alice, unknown, compileBody("TASK-10")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/projects/" + unknown + "/context").with(TestIdentity.as(alice)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/projects/" + unknown + "/context/" + UUID.randomUUID()).with(TestIdentity.as(alice)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Another user's project is not found, never forbidden, on all three routes")
  void anotherUsersProjectIsNotFound() throws Exception {
    String bobsPack = compile(bob, bobProject, "TASK-11: Bob's own").get("packId").asText();

    // 404 and not 403 throughout: a 403 would confirm the id belongs to a real project, which is
    // the one fact an attacker walking UUIDs is trying to learn.
    mvc.perform(compileRequest(alice, bobProject, compileBody("TASK-12")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get("/api/projects/" + bobProject + "/context").with(TestIdentity.as(alice)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/projects/" + bobProject + "/context/" + bobsPack).with(TestIdentity.as(alice)))
        .andExpect(status().isNotFound());

    // And Bob still holds it, so the 404s above are about Alice and not about the pack vanishing.
    mvc.perform(get("/api/projects/" + bobProject + "/context/" + bobsPack).with(TestIdentity.as(bob)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.packId").value(bobsPack));
  }

  @Test
  @DisplayName("A pack that does not exist is not found even in the caller's own project")
  void unknownPackIsNotFound() throws Exception {
    mvc.perform(get("/api/projects/" + aliceProject + "/context/" + UUID.randomUUID()).with(TestIdentity.as(alice)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  @DisplayName("A real pack id under the wrong project is not found, even when both are the caller's")
  void aRealPackUnderTheWrongProjectIsNotFound() throws Exception {
    String packId = compile(alice, aliceProject, "TASK-13: in the right project").get("packId").asText();

    // Both projects are Alice's, so the project gate passes and the pairing is the only thing left
    // to refuse the read. This is the case a project check alone would have let through.
    mvc.perform(get("/api/projects/" + aliceOtherProject + "/context/" + packId).with(TestIdentity.as(alice)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    mvc.perform(get("/api/projects/" + aliceProject + "/context/" + packId).with(TestIdentity.as(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.packId").value(packId));
  }

  @Test
  @DisplayName("An unauthenticated caller reaches none of the three routes")
  void unauthenticatedCallersAreRefused() throws Exception {
    mvc.perform(get("/api/projects/" + aliceProject + "/context")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/projects/" + aliceProject + "/context/" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/projects/" + aliceProject + "/context/compile")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(compileBody("TASK-14")))
        .andExpect(status().isUnauthorized());
  }

  // ---------------------------------------------------------------- negative control

  @Test
  @DisplayName("Negative control: a route that does not exist fails, so the assertions above bite")
  void negativeControlOnANonExistentRoute() throws Exception {
    String packId = compile(alice, aliceProject, "TASK-15: negative control").get("packId").asText();

    // The ownership tests above are satisfied by a 404, and an unmapped URL also produces one — so
    // on status alone they would pass against a controller that was never registered. This is the
    // control that separates the two: the misspelled route answers with no pack in the body, while
    // the real one answers with this exact packId.
    String wrongRoute =
        body(get("/api/projects/" + aliceProject + "/contexts/" + packId).with(TestIdentity.as(alice)));
    assertThat(wrongRoute).doesNotContain(packId);
    assertThat(wrongRoute).doesNotContain("contentFingerprint");

    String realRoute =
        body(get("/api/projects/" + aliceProject + "/context/" + packId).with(TestIdentity.as(alice)));
    assertThat(json.readTree(realRoute).get("packId").asText()).isEqualTo(packId);
    assertThat(json.readTree(realRoute).get("items").size()).isGreaterThan(0);
  }

  // ---------------------------------------------------------------- the limit contract

  /**
   * More packs than the default page, so "the default was applied" and "everything was returned"
   * are different numbers. With twenty packs and a default of twenty they would be the same number,
   * and every assertion below about a defaulted page would pass just as well against a route that
   * had stopped bounding anything at all.
   */
  private static final int PACKS_FOR_THE_CENSUS = ContextPackController.DEFAULT_LIST_LIMIT + 2;

  /** Compiles {@link #PACKS_FOR_THE_CENSUS} packs into Alice's project and returns how many. */
  private int fillTheProject() throws Exception {
    for (int i = 0; i < PACKS_FOR_THE_CENSUS; i++) {
      compile(alice, aliceProject, "TASK-limit-" + i);
    }
    return PACKS_FOR_THE_CENSUS;
  }

  /**
   * What one spelling of {@code limit} produced: the status, and enough of the body to tell two
   * answers apart.
   *
   * <p>A served page is summarised by HOW MANY packs came back, not merely by being a 200 — the
   * whole defect was a 200 carrying a page size the caller never named, and a status-only summary
   * is structurally incapable of seeing that. A refusal is summarised by what a client branches on:
   * the {@code code}, and the field each violation names.
   */
  private String answerFor(String limit) throws Exception {
    MockHttpServletRequestBuilder request =
        get("/api/projects/" + aliceProject + "/context").with(TestIdentity.as(alice));
    if (limit != null) {
      // .param, deliberately, rather than pasting the value into the query string. A value put
      // through URLEncoder and into the URI is then decoded by MockMvc on its own terms: measured
      // on this suite, "+7" arrives at the route as the literal "%2B7" and a fullwidth digit
      // arrives mangled. A census built that way pins the harness's encoding rather than the
      // route's contract, and both of those spellings were being recorded as refusals while the
      // running route accepted them as seven and as two.
      request = request.param("limit", limit);
    }
    MvcResult result = mvc.perform(request).andReturn();
    int status = result.getResponse().getStatus();
    String content = result.getResponse().getContentAsString();
    if (status == 200) {
      return "200 packs=" + json.readTree(content).size();
    }
    JsonNode error = json.readTree(content);
    List<String> fields = new ArrayList<>();
    error.get("violations").forEach(violation -> fields.add(violation.get("field").asText()));
    return status + " " + error.get("code").asText() + " violations=" + fields;
  }

  /** The sentence a refused spelling carried, inside the one shape every refusal has. */
  private String violationMessage(String limit) throws Exception {
    String content =
        body(
            get("/api/projects/" + aliceProject + "/context")
                .with(TestIdentity.as(alice))
                .param("limit", limit));
    return json.readTree(content).get("violations").get(0).get("message").asText();
  }

  @Test
  @DisplayName("Every spelling of limit answers in one contract: a page the caller named, or a 400")
  void theLimitParameterAnswersInOneContract() throws Exception {
    int available = fillTheProject();

    // The required matrix first, then the spellings a reviewer reaches for: a leading sign,
    // surrounding whitespace, both ends of int overflow, a value longer than a long, Unicode digits
    // that Integer.parseInt would accept quite happily, and the notations of other languages.
    Map<String, String> expected = new LinkedHashMap<>();
    String refused = "400 VALIDATION_ERROR violations=[limit]";
    expected.put(null, "200 packs=" + ContextPackController.DEFAULT_LIST_LIMIT);
    expected.put("", refused);
    expected.put("abc", refused);
    expected.put("0x10", refused);
    expected.put("0", refused);
    expected.put("-1", refused);
    expected.put("1", "200 packs=1");
    expected.put("20", "200 packs=20");
    expected.put(String.valueOf(ContextPackController.MAX_LIST_LIMIT), "200 packs=" + available);
    expected.put(String.valueOf(ContextPackController.MAX_LIST_LIMIT + 1), refused);
    expected.put("+7", refused);
    expected.put(" 5", refused);
    expected.put("5 ", refused);
    expected.put(" 5 ", refused);
    expected.put("2147483648", refused);
    expected.put("-2147483649", refused);
    expected.put("9".repeat(30), refused);
    expected.put("２", refused); // fullwidth two
    expected.put("٥", refused); // Arabic-Indic five
    expected.put("1_000", refused);
    expected.put("1e2", refused);
    expected.put("5.5", refused);
    expected.put("1,2", refused);
    expected.put("1;DROP TABLE context_packs", refused);
    // A leading zero is read as decimal and never as octal: 020 is twenty, not sixteen. Pinned
    // because Integer.decode — the obvious parser to reach for, and the one behind the old
    // behaviour — answers sixteen here, and answers sixteen for 0x10 above.
    expected.put("01", "200 packs=1");
    expected.put("020", "200 packs=20");

    Map<String, String> census = new LinkedHashMap<>();
    for (String spelling : expected.keySet()) {
      census.put(spelling, answerFor(spelling));
    }

    assertThat(census)
        .as("one parameter, one contract: a page of the size named, or a 400 naming the field")
        .containsExactlyInAnyOrderEntriesOf(expected);

    // The property, stated apart from the table. The table can be edited to admit a new shape one
    // entry at a time; this cannot, and it is the actual promise — a caller never has to know WHICH
    // kind of wrong their input was in order to parse the answer.
    assertThat(
            census.values().stream()
                .filter(answer -> !answer.startsWith("200"))
                .distinct()
                .toList())
        .as("every refusal is one status, one code and one named field")
        .containsExactly(refused);

    assertThat(census.values())
        .as("no spelling of a query parameter may be a server fault")
        .allSatisfy(answer -> assertThat(answer).doesNotStartWith("5"));

    // The range sentences are reachable, which is the other half of the defect: they were dead
    // strings on annotations whose exception no handler claimed, and no client ever saw them.
    assertThat(violationMessage("0")).isEqualTo("limit must be at least 1");
    assertThat(violationMessage("-1")).isEqualTo("limit must be at least 1");
    assertThat(violationMessage("101")).isEqualTo("limit may not exceed 100");
    assertThat(violationMessage("2147483648"))
        .as("a value past int is a range refusal, not a parse failure and not an overflow")
        .isEqualTo("limit may not exceed 100");
    assertThat(violationMessage("-2147483649")).isEqualTo("limit must be at least 1");
    assertThat(violationMessage("abc"))
        .isEqualTo("limit must be a decimal integer between 1 and 100");

    // AND THE GUARD AGAINST THIS TEST'S OWN DECAY. Every "200 packs=n" above is only meaningful
    // while the project holds more packs than the largest page it asserts. If the fixture shrank to
    // twenty, "packs=20" would be equally true of a defaulted page, of an explicit twenty, and of a
    // route that had stopped bounding anything.
    assertThat(available)
        .as("the fixture must hold more packs than the default page, or those sizes are the same")
        .isGreaterThan(ContextPackController.DEFAULT_LIST_LIMIT);
  }

  @Test
  @DisplayName("An empty limit is refused, not quietly served as though the caller had named none")
  void anEmptyLimitIsNotSilentlyTheDefault() throws Exception {
    fillTheProject();

    // THE DEFECT, ISOLATED. ?limit= was served with the default page size, so a client whose
    // variable interpolated to empty read a page of twenty as the whole list — the exact failure
    // this route's own contract refuses to create for an out-of-range number, arriving by a
    // spelling nobody had enumerated. The two requests must not agree.
    String omitted =
        body(get("/api/projects/" + aliceProject + "/context").with(TestIdentity.as(alice)));
    MvcResult empty =
        mvc.perform(
                get("/api/projects/" + aliceProject + "/context")
                    .with(TestIdentity.as(alice))
                    .param("limit", ""))
            .andReturn();

    assertThat(empty.getResponse().getStatus()).isEqualTo(400);
    assertThat(empty.getResponse().getContentAsString()).isNotEqualTo(omitted);
    assertThat(json.readTree(omitted).size())
        .as("the omitted case still works, so the refusal above is about the empty value")
        .isEqualTo(ContextPackController.DEFAULT_LIST_LIMIT);
  }

  @Test
  @DisplayName("No refused limit is ever converted into the default, which is the worse failure")
  void aRefusedLimitNeverBecomesADefaultedPage() throws Exception {
    fillTheProject();

    // Written as a loop over refusals rather than as one example, because this failure mode is
    // silent by construction: a route that fell back to the default for a value it could not read
    // would answer 200 with twenty packs and be indistinguishable from a caller who asked for
    // nothing.
    for (String bad : new String[] {"", " ", "abc", "0", "-1", "101", "0x10", "+7", "1e2"}) {
      MvcResult result =
          mvc.perform(
                  get("/api/projects/" + aliceProject + "/context")
                      .with(TestIdentity.as(alice))
                      .param("limit", bad))
              .andReturn();
      assertThat(result.getResponse().getStatus())
          .as("limit=%s must be refused, never answered with a page the caller did not name", bad)
          .isEqualTo(400);
      assertThat(result.getResponse().getContentAsString())
          .as("a refusal carries no packs at all")
          .doesNotContain("contentFingerprint");
    }
  }

  @Test
  @DisplayName("A refused limit on an unreadable project is still 404, never 400")
  void ownershipIsDecidedBeforeTheLimitIs() throws Exception {
    // The ordering the annotations decided for us, and decided backwards: parameter validation runs
    // before the method body, so under the old route a stranger learned 400 from ?limit=0 and 404
    // from ?limit=20 — the answer depended on the query string. The gate is now unconditional.
    // Asserted for a project that exists and is not the caller's AND for one that does not exist,
    // because the property is "404 in every shape", not "404 for a stranger".
    for (UUID project : new UUID[] {bobProject, UUID.randomUUID()}) {
      for (String spelling : new String[] {"0", "-1", "101", "", "abc", "0x10", "20"}) {
        mvc.perform(
                get("/api/projects/" + project + "/context")
                    .with(TestIdentity.as(alice))
                    .param("limit", spelling))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
      }
    }
  }
}
