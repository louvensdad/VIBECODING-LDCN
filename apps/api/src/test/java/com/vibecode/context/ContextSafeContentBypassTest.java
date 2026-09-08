package com.vibecode.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.context.application.compiler.ContextPackAssembler;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextPack;
import com.vibecode.context.domain.ContextPolicyVersion;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.context.domain.RedactedContextItem;
import com.vibecode.context.infrastructure.persistence.ContextPackEntity;
import com.vibecode.context.infrastructure.persistence.ContextPackRepository;
import com.vibecode.project.application.ProjectService;
import com.vibecode.project.domain.Project;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.support.TestIdentity;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.RiskLevel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every way round the redaction step that a reviewer would try, tried here first.
 *
 * <p>{@link ContextPackPlaintextLeakTest} asks whether the pipeline redacts. This asks the harder
 * question: whether anything can <em>reach the database without using the pipeline</em>. The two are
 * not the same test. A pipeline that redacts perfectly is worth nothing if a caller can build a pack
 * beside it, and until CTX-SAFE-01 a caller could — {@code CompiledContextPack} accepted bare items
 * and redaction was a stage the compiler happened to run first.
 *
 * <p>The attempts below are the four an adversarial reviewer named: build a pack by hand, skip the
 * redactor, persist a raw {@code String}, and find an alternative factory. Each is answered by the
 * thing that actually stops it, and the answers are of three different strengths, which is worth
 * being precise about rather than blurring into "it is safe":
 *
 * <ul>
 *   <li><b>Compiler.</b> {@link AdmittedContextItem} takes a {@link RedactedContextItem}. A pack of
 *       raw items, or a row built from a {@code String}, does not compile. Nothing in this file can
 *       demonstrate that by executing it — the demonstration is that the code cannot be written —
 *       so the shapes are asserted by reflection instead, and they are what a compile error would
 *       have been about.
 *   <li><b>Build.</b> The two mints on {@code RedactedContextItem} are fenced to one package each
 *       by {@code ContextModuleArchitectureTest}, and the return type is fenced across the whole
 *       application. Java without a JPMS module cannot say "one other package may call this", so
 *       this is where that sentence lives.
 *   <li><b>Nothing.</b> Reflection forges the wrapper, and the last test does exactly that and
 *       persists the result. That is not a defect being reported; it is true of every type in the
 *       language, and a claim of impossibility would be the dishonest version of this file.
 * </ul>
 *
 * <p>The claim being defended, exactly: <b>no ordinary production path persists the fixture
 * unredacted.</b>
 */
@SpringBootTest
class ContextSafeContentBypassTest {

  /** The agreed fixture. Synthetic; it opens nothing. */
  private static final String FIXTURE = "vc_context_constructor_secret_847292";

  private static final ContextBudget BUDGET = new ContextBudget(500, 1_000_000L, 2_000_000L);

  @Autowired ContextPackAssembler assembler;
  @Autowired ContextPackRepository packs;
  @Autowired TestIdentity identity;
  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TaskService tasks;
  @Autowired JdbcTemplate jdbc;

  private UUID projectId;

  @BeforeEach
  void createAProjectWhoseRecordsCarryTheFixture() {
    identity.createAndAuthenticate("bypass-owner");
    Project project =
        projects.create(
            "Bypass fixture project",
            "A project used to try to get a secret past the redaction boundary",
            "Guide a solo developer through building a small tool");
    projectId = project.getId();

    RoadmapPhase phase = roadmaps.addPhase(projectId, 1, "Foundations", "Set the shape");
    tasks.addTask(
        projectId,
        phase.getId(),
        1,
        "Wire the deployment",
        "Run the migration with TOKEN=" + FIXTURE + " exported first.",
        RiskLevel.LOW);
  }

  @AfterEach
  void clearIdentity() {
    identity.clear();
  }

  @Test
  @DisplayName("Attempt 1: build a pack by hand — the constructor refuses raw items")
  void aHandBuiltPackCannotCarryRawItems() {
    // What a caller would write:
    //
    //     new AdmittedContextItem(rawItem, allow)          // does not compile
    //     new CompiledContextPack(..., List.of(rawItem))   // does not compile
    //
    // Neither line can appear in this file, so the shape those errors are about is asserted here.
    // Before CTX-SAFE-01 both compiled, and the pack they produced was persistable.
    Constructor<?>[] admitted = AdmittedContextItem.class.getDeclaredConstructors();
    assertThat(admitted).hasSize(1);
    assertThat(admitted[0].getParameterTypes())
        .as("a raw item has no route into an admitted one")
        .contains(RedactedContextItem.class)
        .doesNotContain(ContextItem.class, ContextPack.class, String.class);

    Constructor<?>[] compiled = CompiledContextPack.class.getDeclaredConstructors();
    assertThat(compiled).hasSize(1);
    assertThat(compiled[0].getParameterTypes())
        .as("and no pre-built pack may be supplied alongside")
        .doesNotContain(ContextPack.class, ContextItem.class);
  }

  @Test
  @DisplayName("Attempt 2: skip the redactor — its output is the only thing that fits")
  void theRedactorCannotBeSkippedOnTheProductionPath() {
    // ContextRedaction.redact returns the wrapper, and the wrapper is what AdmittedContextItem
    // takes. Removing the call from the compiler does not produce a pack of raw items any more;
    // it produces a type error. The two mints that could produce a wrapper without redacting are
    // named, private-constructed, and fenced to one package each by ContextModuleArchitectureTest
    // — onlyTheRedactionStepMintsRedactedContent and onlyPersistenceRehydratesStoredContent.
    for (Constructor<?> constructor : RedactedContextItem.class.getDeclaredConstructors()) {
      assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
    }

    List<Method> mints =
        Arrays.stream(RedactedContextItem.class.getDeclaredMethods())
            .filter(method -> Modifier.isStatic(method.getModifiers()))
            .filter(method -> method.getReturnType().equals(RedactedContextItem.class))
            .filter(method -> !method.isSynthetic())
            .toList();
    assertThat(mints)
        .extracting(Method::getName)
        .containsExactlyInAnyOrder("producedByRedaction", "rehydratedFromStorage");
  }

  @Test
  @DisplayName("Attempt 3: persist a raw String — no entity takes one")
  void noEntityAcceptsRawText() {
    // The row is built from an AdmittedContextItem and the pack row from a CompiledContextPack.
    // There is no setter, no builder and no factory anywhere on either that takes text.
    List<Method> packFactories =
        Arrays.stream(ContextPackEntity.class.getDeclaredMethods())
            .filter(method -> Modifier.isStatic(method.getModifiers()))
            .filter(method -> method.getReturnType().equals(ContextPackEntity.class))
            .toList();
    assertThat(packFactories).hasSize(1);
    assertThat(packFactories.get(0).getParameterTypes()).containsExactly(CompiledContextPack.class);

    for (Method method : ContextPackEntity.class.getDeclaredMethods()) {
      if (method.isSynthetic()) {
        continue;
      }
      assertThat(method.getParameterTypes())
          .as("ContextPackEntity.%s must not accept text", method.getName())
          .doesNotContain(String.class);
    }
  }

  @Test
  @DisplayName("Attempt 4: the one production route stores the marker and not the fixture")
  void theOrdinaryPathRedactsTheFixtureBeforeItReachesARow() {
    // The positive half. Everything above says a bypass will not compile; this says the path that
    // does compile behaves. The fixture is in a task objective the policy admits in full, and it is
    // in the task reference, which is not an item and is redacted in the compiler.
    CompiledContextPack compiled =
        assembler.assemble(projectId, "TASK-99: deploy with TOKEN=" + FIXTURE, BUDGET);

    assertThat(compiled.admittedItems()).isNotEmpty();
    assertThat(compiled.canonicalPayload().value()).doesNotContain(FIXTURE);
    assertThat(rawOccurrencesIn("context_packs")).isZero();
    assertThat(rawOccurrencesIn("context_pack_items")).isZero();

    Integer marked =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM context_pack_items i JOIN context_packs p ON p.id = i.pack_id"
                + " WHERE p.project_id = ? AND i.content LIKE '%[REDACTED]%'",
            Integer.class,
            projectId);
    assertThat(marked)
        .as("the value was replaced with a marker, not the whole item quietly dropped")
        .isPositive();
  }

  @Test
  @DisplayName("The limit: reflection forges the wrapper and the row then holds the fixture")
  void reflectionCanStillForgeAWrapperAndThatIsSaidOutLoud() {
    // This test asserts the boundary's limit rather than its strength, and it is here because the
    // alternative is a file that reads as a proof of impossibility. It is not one. Reflection can
    // construct almost any Java object, and no design in this language can prevent that; what a
    // boundary can do is make every *ordinary* route go through it, which the four attempts above
    // are about.
    ContextItem raw =
        new ContextItem(
            "forged-1",
            ContextKind.DECISION,
            "Forged label",
            "Deploy with TOKEN=" + FIXTURE,
            new ContextProvenance(
                ContextSource.of(ContextSourceType.BRAIN_ENTRY, "forged-entry"),
                projectId,
                Instant.parse("2026-01-01T00:00:00Z")));

    RedactedContextItem forged;
    try {
      Constructor<RedactedContextItem> constructor =
          RedactedContextItem.class.getDeclaredConstructor(ContextItem.class);

      // Ordinary access fails, which is the point: the forge is not something a caller does by
      // accident or by convenience. It takes a deliberate setAccessible.
      assertThatThrownBy(() -> constructor.newInstance(raw))
          .isInstanceOf(IllegalAccessException.class);

      constructor.setAccessible(true);
      forged = constructor.newInstance(raw);
    } catch (ReflectiveOperationException cannotForge) {
      throw new AssertionError(
          "The forge itself failed, which means this test can no longer say anything about the"
              + " limit it exists to state",
          cannotForge);
    }

    CompiledContextPack forgedPack =
        new CompiledContextPack(
            UUID.randomUUID(),
            projectId,
            "TASK-FORGE",
            Instant.parse("2026-01-01T00:00:00Z"),
            BUDGET,
            ContextPolicyVersion.CURRENT,
            List.of(
                new AdmittedContextItem(
                    forged,
                    ContextAdmission.allow(
                        "test.forged", "Constructed by reflection to demonstrate the limit."))));
    packs.saveAndFlush(ContextPackEntity.from(forgedPack));

    Integer forgedRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ? AND content LIKE ?",
            Integer.class,
            forgedPack.packId(),
            "%" + FIXTURE + "%");
    assertThat(forgedRows)
        .as("stated rather than hidden: a reflective forge does reach the table")
        .isEqualTo(1);
  }

  /** Every value of every column of one context table, counted for the raw fixture. */
  private int rawOccurrencesIn(String table) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT t.* FROM "
                + table
                + " t "
                + ("context_packs".equals(table)
                    ? "WHERE t.project_id = ?"
                    : "JOIN context_packs p ON p.id = t.pack_id WHERE p.project_id = ?"),
            projectId);
    assertThat(rows).as("the compiled pack should have been written").isNotEmpty();

    int occurrences = 0;
    for (Map<String, Object> row : rows) {
      for (Object value : row.values()) {
        if (value != null && String.valueOf(value).contains(FIXTURE)) {
          occurrences++;
        }
      }
    }
    return occurrences;
  }
}
