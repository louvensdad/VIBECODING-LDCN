package com.vibecode.context.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecode.identity.domain.User;
import com.vibecode.project.application.ProjectService;
import com.vibecode.support.MutableClock;
import com.vibecode.support.TestIdentity;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the list route costs, and what order it answers in.
 *
 * <p>Both properties are here because both were claimed in prose before anything checked them, and
 * both were wrong in the same direction: the route did whatever the repository happened to do.
 *
 * <p><b>Cost.</b> Packs are append-only by design — a second compile over unchanged state produces a
 * second pack rather than replacing the first — so an unbounded list route grows without ceiling for
 * the life of a project, and the caller who pays for it is the owner. The first measurement of forty
 * packs was 620 KB over 42 statements, because {@code ContextPackEntity.items} is {@code EAGER} and
 * a collection query loads each pack's items in a query of its own.
 *
 * <p><b>Order.</b> "Newest first" was documented and unenforced. The suite runs on a frozen clock, so
 * every pack in it carries an identical {@code assembledAt} and a sort on that column alone leaves
 * the order to the database — which returned insertion order, the exact reverse of the guarantee.
 * The test below advances the clock between compiles so the two orders are distinguishable at all,
 * and asserts a known sequence rather than a set.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContextPackListBoundsTest {

  /** Enough packs that a per-pack query is unmistakable against a constant one. */
  private static final int PACKS = 40;

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TestIdentity identity;
  @Autowired ProjectService projects;
  @Autowired MutableClock clock;
  @Autowired EntityManagerFactory entityManagerFactory;

  private User alice;
  private UUID project;

  @BeforeEach
  void setUp() {
    alice = identity.createUser("alice");
    identity.authenticateAs(alice);
    project =
        projects
            .create("Bounds", "A project to compile many packs into", "Ship a tool")
            .getId();
    identity.clear();
  }

  @AfterEach
  void tearDown() {
    identity.clear();
    // The clock is a shared singleton and this class moves it. Left advanced, it would hand every
    // later test in the same JVM a future instant.
    clock.reset();
  }

  private String compile(String taskReference) throws Exception {
    String response =
        mvc.perform(
                post("/api/projects/" + project + "/context/compile")
                    .with(TestIdentity.as(alice))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"taskReference\":\"" + taskReference + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).get("packId").asText();
  }

  private static List<String> packIds(JsonNode listed) {
    List<String> ids = new ArrayList<>();
    listed.forEach(node -> ids.add(node.get("packId").asText()));
    return ids;
  }

  @Test
  @DisplayName("The list is bounded and costs a constant number of queries, not one per pack")
  void theListIsBoundedAndDoesNotScaleItsQueries() throws Exception {
    for (int i = 0; i < PACKS; i++) {
      clock.advance(Duration.ofSeconds(1));
      compile("TASK-" + i);
    }

    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean wasEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    statistics.clear();

    String response;
    try {
      response =
          mvc.perform(get("/api/projects/" + project + "/context").with(TestIdentity.as(alice)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
    } finally {
      statistics.setStatisticsEnabled(wasEnabled);
    }

    long queries = statistics.getPrepareStatementCount();
    long entities = statistics.getEntityLoadCount();
    System.out.println(
        "[CTX-07] GET /context after "
            + PACKS
            + " packs -> bytes="
            + response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + " queries="
            + queries
            + " entitiesLoaded="
            + entities
            + " packsReturned="
            + packIds(json.readTree(response)).size());

    // The default page, not the whole history. Forty packs exist; the caller who asked for no
    // particular number gets the newest page of them.
    assertThat(packIds(json.readTree(response)))
        .hasSize(ContextPackController.DEFAULT_LIST_LIMIT);

    // The bound that actually matters. A limit alone would still issue one query per pack in the
    // page, so this asserts a small constant rather than "fewer than before": one query for the
    // page of ids, one to fetch those packs with their items, and the reads around them.
    assertThat(queries).isLessThanOrEqualTo(8);

    // And the proof that the constant is a constant. A per-pack query would make these two differ
    // by 35; "fewer statements than before" would not have caught a page size that merely moved the
    // N+1 behind a smaller N.
    assertThat(queriesForLimit(5)).isEqualTo(queriesForLimit(40));
  }

  /** Statements issued while serving the list route at one page size. */
  private long queriesForLimit(int limit) throws Exception {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean wasEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    statistics.clear();
    try {
      mvc.perform(
              get("/api/projects/" + project + "/context?limit=" + limit)
                  .with(TestIdentity.as(alice)))
          .andExpect(status().isOk());
      long queries = statistics.getPrepareStatementCount();
      System.out.println("[CTX-07] limit=" + limit + " -> queries=" + queries);
      return queries;
    } finally {
      statistics.setStatisticsEnabled(wasEnabled);
    }
  }

  @Test
  @DisplayName("An explicit limit is honoured, and one outside the accepted range is refused")
  void theLimitIsHonouredAndBounded() throws Exception {
    for (int i = 0; i < 5; i++) {
      clock.advance(Duration.ofSeconds(1));
      compile("TASK-" + i);
    }

    JsonNode three =
        json.readTree(
            mvc.perform(
                    get("/api/projects/" + project + "/context?limit=3")
                        .with(TestIdentity.as(alice)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(packIds(three)).hasSize(3);

    // Zero, negative and past the ceiling are all refused rather than clamped. Silently returning
    // a hundred packs to a caller who asked for a thousand would be the API answering a question
    // nobody asked, and the caller would have no way to know it had been narrowed.
    for (String bad : new String[] {"0", "-1", "101", "not-a-number"}) {
      mvc.perform(
              get("/api/projects/" + project + "/context?limit=" + bad)
                  .with(TestIdentity.as(alice)))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  @DisplayName("Packs come back newest first, as a known sequence and not as a set")
  void packsComeBackNewestFirst() throws Exception {
    // The clock is advanced between compiles on purpose. Without it every pack in this suite
    // carries the same assembledAt — the suite's clock is frozen — and an ordering assertion could
    // not distinguish newest-first from oldest-first at all. That is precisely how the guarantee
    // came to be documented without ever being checked.
    List<String> inCreationOrder = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      clock.advance(Duration.ofSeconds(1));
      inCreationOrder.add(compile("TASK-" + i));
    }

    List<String> newestFirst = new ArrayList<>(inCreationOrder);
    java.util.Collections.reverse(newestFirst);

    JsonNode listed =
        json.readTree(
            mvc.perform(get("/api/projects/" + project + "/context").with(TestIdentity.as(alice)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    // containsExactly, not containsExactlyInAnyOrder. The latter is structurally incapable of
    // failing on ordering, which is the only thing this test is about.
    assertThat(packIds(listed)).containsExactlyElementsOf(newestFirst);
    assertThat(packIds(listed)).isNotEqualTo(inCreationOrder);
  }

  @Test
  @DisplayName("Packs sharing an assembly instant still come back newest first, not merely stably")
  void tiedInstantsStillComeBackNewestFirst() throws Exception {
    // THE FROZEN-CLOCK CASE, which is where the original counterexample came from: five packs, one
    // assembledAt, the ordinary situation for every test in this suite.
    //
    // An earlier version of this test asserted only that two reads agreed with each other. That was
    // a true statement about a property nothing threatens — H2 returns insertion order
    // deterministically — so it passed with the tiebreaker deleted from the query, i.e. with the
    // reported defect fully reinstated. Repeatability is not the property at risk; being STABLY
    // WRONG is exactly what the defect was.
    //
    // The stronger property is available and measured. createdAt is the wall-clock moment the row
    // was written, and it does not tie when assembledAt does: five sequential compiles produced one
    // distinct assembled_at and five distinct created_at values 100-200ms apart, against a column
    // that stores microseconds. So under a tied assembly instant there IS a correct age-ordered
    // answer — the reverse of creation order — and this test asserts that sequence.
    List<String> inCreationOrder = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      inCreationOrder.add(compile("TASK-tied-" + i));
    }

    List<String> firstRead = listedPackIds();

    // The tie is asserted rather than assumed. If a future change gave each pack its own
    // assembledAt, the primary key would do all the work and this test would quietly become a
    // duplicate of packsComeBackNewestFirst while still passing — covering the tiebreaker nowhere.
    assertThat(distinctAssembledAt(firstRead.size()))
        .as("the five packs must share one assembledAt, or this test is not about ties at all")
        .isEqualTo(1);

    List<String> newestFirst = new ArrayList<>(inCreationOrder);
    java.util.Collections.reverse(newestFirst);
    assertThat(firstRead).containsExactlyElementsOf(newestFirst);
    assertThat(firstRead).isNotEqualTo(inCreationOrder);

    // Repeatability is kept as well, because it is a separate promise: a client rendering this list
    // must not see it reshuffle between two reads of unchanged data.
    assertThat(listedPackIds()).containsExactlyElementsOf(firstRead);
  }

  /** The pack ids the list route reports for this project, in the order it reported them. */
  private List<String> listedPackIds() throws Exception {
    return packIds(
        json.readTree(
            mvc.perform(get("/api/projects/" + project + "/context").with(TestIdentity.as(alice)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()));
  }

  /** How many distinct {@code assembledAt} values the newest {@code expected} packs carry. */
  private long distinctAssembledAt(int expected) throws Exception {
    JsonNode listed =
        json.readTree(
            mvc.perform(get("/api/projects/" + project + "/context").with(TestIdentity.as(alice)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    List<String> instants = new ArrayList<>();
    listed.forEach(node -> instants.add(node.get("assembledAt").asText()));
    assertThat(instants).hasSize(expected);
    return instants.stream().distinct().count();
  }

  @Test
  @DisplayName("The limit does not become a way around ownership")
  void theLimitDoesNotWidenWhatIsReadable() throws Exception {
    User bob = identity.createUser("bob");

    mvc.perform(
            get("/api/projects/" + project + "/context?limit=100").with(TestIdentity.as(bob)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
