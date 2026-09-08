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
import com.vibecode.context.infrastructure.persistence.ContextPackRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
  @Autowired ContextPackRepository packs;
  @Autowired javax.sql.DataSource dataSource;

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

    // HOW MUCH WAS READ, which is the number the query count cannot see. Asserted below as a
    // property rather than a constant — see readsScaleWithThePageAndNotWithTheProject.
    assertThat(entities).isPositive();

    // And the proof that the constant is a constant. A per-pack query would make these two differ
    // by 35; "fewer statements than before" would not have caught a page size that merely moved the
    // N+1 behind a smaller N.
    assertThat(cost(5).queries()).isEqualTo(cost(40).queries());
  }

  @Test
  @DisplayName("A smaller page reads less, which is what the statement count cannot see")
  void readsScaleWithThePageAndNotWithTheProject() throws Exception {
    for (int i = 0; i < PACKS; i++) {
      clock.advance(Duration.ofSeconds(1));
      compile("TASK-" + i);
    }

    // THE MISTAKE THIS EXISTS TO CATCH. Replacing the two-query route with a single paged
    // `left join fetch` leaves every statement-level metric looking the same or better — it issues
    // TWO queries rather than three — because a collection fetch cannot be paged in SQL. Hibernate
    // says so itself and then does it anyway:
    //
    //   HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory
    //
    // It reads all forty packs with their items and discards twenty. Measured: 3 queries and 101
    // entities for the real route, 2 queries and 201 entities for the mutation.
    //
    // WHY A PROPERTY AND NOT A CONSTANT. The obvious assertion is a ceiling on entities loaded, and
    // choosing its value is where it goes wrong: the page holds 20 packs plus their items, which is
    // 101 today, and how many items a pack carries is the engine's business and will change. A
    // ceiling below 101 fails on correct code; one above 201 cannot fail on the mutation; anything
    // between is a magic number nobody can maintain, sitting between two figures that both move.
    //
    // The property has no such problem and is the thing actually being promised: reads scale with
    // the PAGE, not with the project. Under the mutation both page sizes read the whole project, so
    // the two measurements are equal and this fails — with no constant to keep up to date.
    Cost small = cost(5);
    Cost large = cost(PACKS);

    assertThat(small.entities())
        .as("a five-pack page must read less than a forty-pack page; equal means both read"
            + " the whole project and paged in memory")
        .isLessThan(large.entities());

    // The page is what is read, so the ratio tracks the page sizes rather than being merely
    // unequal. Loose enough not to encode the item count, tight enough that reading everything and
    // discarding most of it cannot satisfy it.
    assertThat(small.entities()).isLessThan(large.entities() / 2);
  }

  /** What one list request cost: statements issued and entities read. */
  private record Cost(long queries, long entities) {}

  /** Serves the list route at one page size and reports what it cost. */
  private Cost cost(int limit) throws Exception {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean wasEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    statistics.clear();
    try {
      mvc.perform(
              get("/api/projects/" + project + "/context?limit=" + limit)
                  .with(TestIdentity.as(alice)))
          .andExpect(status().isOk());
      Cost cost =
          new Cost(statistics.getPrepareStatementCount(), statistics.getEntityLoadCount());
      System.out.println(
          "[CTX-07] limit=" + limit + " -> queries=" + cost.queries()
              + " entitiesLoaded=" + cost.entities());
      return cost;
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
  @DisplayName("With both time keys tied, the id decides — the third sort key, reached directly")
  void theThirdSortKeyOrdersPacksWhenBothTimeKeysTie() throws Exception {
    // THE THIRD KEY, closed at the repository rather than through the API.
    //
    // I previously declared this uncovered and called it unreachable. Unreachable THROUGH THE API
    // is true — createdAt is Instant.now() taken inside ContextPackEntity.from, and two HTTP
    // compiles measured 100-200ms apart against a microsecond column, so they cannot tie. Calling
    // it unclosable was a notch stronger than the code supports: the condition is one UPDATE away,
    // and the repository method can be called without a controller.
    //
    // Forcing created_at equal is the whole point rather than a shortcut. The rows are written by
    // the real write path and only the one column the API cannot control is then tied, which is
    // exactly the state the third key exists for and the only way to observe it deciding anything.
    for (int i = 0; i < 8; i++) {
      compile("TASK-third-key-" + i);
    }

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update(
        "update context_packs set created_at = ? where project_id = ?",
        java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z")),
        project);

    // Both time keys now tie: assembledAt from the frozen clock, createdAt from the update above.
    // Asserted, not assumed — if either stopped tying, the third key would go unexercised again and
    // this test would pass while covering nothing, which is the decay mode the tie test already
    // guards against.
    assertThat(
            jdbc.queryForObject(
                "select count(distinct assembled_at) + count(distinct created_at)"
                    + " from context_packs where project_id = ?",
                Integer.class,
                project))
        .as("both time keys must tie, or the id is not what is being tested")
        .isEqualTo(2);

    List<UUID> ordered =
        packs.findPackIdsByProjectNewestFirst(project, PageRequest.of(0, 50));

    // Compared against the database's own descending id order rather than against Java's
    // UUID.compareTo, which orders by SIGNED longs and does not agree with how the column is
    // compared. The point is not which collation is right; it is that the repository's answer is
    // decided by the id at all. Without `p.id desc` the query returns insertion order, and for
    // eight random ids that differs from id order with overwhelming probability.
    List<UUID> byIdDescending =
        jdbc.queryForList(
            "select id from context_packs where project_id = ? order by id desc",
            UUID.class,
            project);

    assertThat(ordered).hasSize(8).containsExactlyElementsOf(byIdDescending);
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
