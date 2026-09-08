package com.vibecode.context.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.context.application.redaction.ContextRedaction;
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
import com.vibecode.project.application.ProjectService;
import com.vibecode.support.TestIdentity;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What a stored pack must still be when it comes back out.
 *
 * <p>A snapshot is only evidence if it survives the round trip unchanged. Two things are easy to
 * lose without noticing: the canonical order, which disappears the moment a list is persisted
 * without an order column, and provenance, which degrades quietly into "it came from a brain entry"
 * when a field such as the source version is dropped. Both are asserted against a database that has
 * actually been written to and read from, not against an object still in memory.
 *
 * <p>All fixtures are synthetic. Nothing in a context pack may resemble credential material.
 */
@SpringBootTest
class ContextPackPersistenceTest {

  /** Fixed rather than now(): a round-trip assertion should not depend on clock precision. */
  private static final Instant OBSERVED_AT = Instant.parse("2026-03-01T10:15:30Z");

  private static final Instant ASSEMBLED_AT = Instant.parse("2026-03-01T10:16:00Z");

  /** Generous, because this test is about persistence and not about where a budget binds. */
  private static final ContextBudget BUDGET = new ContextBudget(50, 100_000L, 200_000L);

  /**
   * A stand-in admission, so these fixtures can be stored at all.
   *
   * <p>Nothing here is testing the policy: what these tests are about is the round trip. But there
   * is no way to store a pack without an admission per item, which is the point of {@link
   * AdmittedContextItem} - so a fixture has to supply one, and it says out loud that it is a
   * fixture rather than borrowing a real rule id that a reader might then go looking for.
   */
  private static final ContextAdmission FIXTURE_ADMISSION =
      ContextAdmission.allow(
          "test.fixture.persistence",
          "A synthetic admission used by the persistence round-trip fixtures.");

  @Autowired ContextPackRepository packs;
  @Autowired ProjectService projects;
  @Autowired TestIdentity identity;
  @Autowired JdbcTemplate jdbc;

  private UUID projectId;

  @BeforeEach
  void createProject() {
    identity.createAndAuthenticate("pack-owner");
    projectId = projects.create("Context pack project", "", "Uma ideia sintetica").getId();
  }

  @AfterEach
  void signOut() {
    identity.clear();
  }

  private ContextItem item(
      String id, ContextKind kind, ContextSourceType sourceType, String sourceId, Integer version) {
    ContextSource source = new ContextSource(sourceType, sourceId, version);
    return new ContextItem(
        id,
        kind,
        "Label for " + id,
        "Synthetic content for " + id,
        new ContextProvenance(source, projectId, OBSERVED_AT));
  }

  private ContextPack packOf(List<ContextItem> items) {
    return new ContextPack(UUID.randomUUID(), projectId, "TASK-42", ASSEMBLED_AT, BUDGET, items);
  }

  /**
   * The compiled form of a fixture pack, since a bare pack is not storable.
   *
   * <p>Every item gets {@link #FIXTURE_ADMISSION}. The admissions are uniform on purpose: these
   * tests assert about order, provenance and widths, and varying the rule id per item would only
   * add a second thing that could differ between what was written and what came back.
   */
  private CompiledContextPack compiled(ContextPack pack) {
    return new CompiledContextPack(
        pack.packId(),
        pack.projectId(),
        pack.taskReference(),
        pack.assembledAt(),
        pack.budget(),
        ContextPolicyVersion.CURRENT,
        pack.items().stream()
            .map(item -> new AdmittedContextItem(ContextRedaction.redact(item), FIXTURE_ADMISSION))
            .toList());
  }

  private List<String> idsOf(ContextPack pack) {
    return pack.items().stream().map(ContextItem::id).toList();
  }

  @Test
  @DisplayName("A pack read back holds its items in the same canonical order it was written in")
  void itemOrderSurvivesTheRoundTrip() {
    List<ContextItem> items =
        new ArrayList<>(
            List.of(
                item("item-a", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-1", null),
                item("item-b", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-9", 3),
                item("item-c", ContextKind.DECISION, ContextSourceType.BRAIN_ENTRY, "entry-2", 1),
                item("item-d", ContextKind.VISION, ContextSourceType.PROJECT, "project-1", null),
                item("item-e", ContextKind.ERROR, ContextSourceType.ACTIVE_ERRORS, "err-7", null)));
    // Shuffled deliberately, with a fixed seed so the fixture is the same every run: if the round
    // trip were preserving insertion order rather than the canonical one, an already-sorted
    // fixture would hide it.
    Collections.shuffle(items, new Random(20260301L));

    ContextPack written = packOf(items);
    packs.save(ContextPackEntity.from(compiled(written)));

    ContextPack read = packs.findById(written.packId()).orElseThrow().toDomain();

    assertThat(idsOf(read)).containsExactlyElementsOf(idsOf(written));
    assertThat(read.items()).containsExactlyElementsOf(written.items());

    // And the order is a fact of the database rather than of the mapping: the stored positions are
    // the canonical order, so any other reader of these rows sees the same sequence.
    List<String> byStoredPosition =
        jdbc.queryForList(
            "SELECT item_id FROM context_pack_items WHERE pack_id = ? ORDER BY item_position",
            String.class,
            written.packId());
    assertThat(byStoredPosition).containsExactlyElementsOf(idsOf(written));
  }

  @Test
  @DisplayName("Provenance survives the round trip in full, versioned and unversioned alike")
  void provenanceSurvivesTheRoundTrip() {
    ContextItem versioned =
        item("versioned", ContextKind.DECISION, ContextSourceType.BRAIN_ENTRY, "entry-77", 4);
    ContextItem unversioned =
        item(
            "unversioned",
            ContextKind.EVIDENCE,
            ContextSourceType.LATEST_EVIDENCE,
            "evidence-3",
            null);

    ContextPack written = packOf(List.of(versioned, unversioned));
    packs.save(ContextPackEntity.from(compiled(written)));

    ContextPack read = packs.findById(written.packId()).orElseThrow().toDomain();

    ContextItem readVersioned = itemNamed(read, "versioned");
    assertThat(readVersioned.provenance().sourceType()).isEqualTo(ContextSourceType.BRAIN_ENTRY);
    assertThat(readVersioned.provenance().sourceId()).isEqualTo("entry-77");
    assertThat(readVersioned.provenance().sourceVersion()).contains(4);
    assertThat(readVersioned.provenance().projectId()).isEqualTo(projectId);
    assertThat(readVersioned.provenance().recordedAt()).isEqualTo(OBSERVED_AT);

    ContextItem readUnversioned = itemNamed(read, "unversioned");
    // Absent, not zero: a record with no revision must not come back claiming to have one.
    assertThat(readUnversioned.provenance().sourceVersion()).isEmpty();
    assertThat(readUnversioned.provenance().sourceType())
        .isEqualTo(ContextSourceType.LATEST_EVIDENCE);
    assertThat(readUnversioned.provenance().sourceId()).isEqualTo("evidence-3");
    assertThat(readUnversioned.provenance().recordedAt()).isEqualTo(OBSERVED_AT);

    // The pack's own fields come back too, budget included.
    assertThat(read.projectId()).isEqualTo(projectId);
    assertThat(read.taskReference()).isEqualTo("TASK-42");
    assertThat(read.assembledAt()).isEqualTo(ASSEMBLED_AT);
    assertThat(read.budget()).isEqualTo(BUDGET);
  }

  @Test
  @DisplayName("Two packs with identical content both persist: the fingerprint is not an identity")
  void identicalContentIsNotADuplicate() {
    List<ContextItem> items =
        List.of(
            item("shared-1", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1", 1),
            item("shared-2", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-1", null));

    // Same items, same project, same task — assembled twice, as a rebuild from unchanged state
    // genuinely is. Only the pack id and the assembly time differ.
    ContextPack first = packOf(items);
    ContextPack second =
        new ContextPack(
            UUID.randomUUID(), projectId, "TASK-42", ASSEMBLED_AT.plusSeconds(600), BUDGET, items);

    assertThat(first.contentFingerprint()).isEqualTo(second.contentFingerprint());
    assertThat(first.packId()).isNotEqualTo(second.packId());

    packs.save(ContextPackEntity.from(compiled(first)));
    packs.save(ContextPackEntity.from(compiled(second)));

    // Both rows exist. A unique constraint on the fingerprint would have rejected the second, and a
    // rebuild of unchanged context would have become an error instead of a second snapshot.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM context_packs WHERE content_fingerprint = ?",
                Integer.class,
                first.contentFingerprint()))
        .isEqualTo(2);

    // Each is still found by its own id — the only handle a pack has — and only within its project.
    assertThat(packs.findById(first.packId())).isPresent();
    assertThat(packs.findById(second.packId())).isPresent();
    assertThat(packs.findByIdAndProjectId(first.packId(), projectId)).isPresent();
    assertThat(packs.findByIdAndProjectId(second.packId(), UUID.randomUUID())).isEmpty();
    assertThat(packs.findByProjectIdOrderByAssembledAtDesc(projectId))
        .extracting(ContextPackEntity::getId)
        .containsExactly(second.packId(), first.packId());
  }

  @Test
  @DisplayName("A pack whose stored positions were altered refuses to load")
  void tamperedOrderIsRejected() {
    ContextPack written =
        packOf(
            List.of(
                item("tamper-a", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-1", null),
                item("tamper-b", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1", 1),
                item("tamper-c", ContextKind.ERROR, ContextSourceType.ACTIVE_ERRORS, "err-1", null)));
    packs.save(ContextPackEntity.from(compiled(written)));

    // Swap the first two positions behind the mapping's back, through a temporary value because
    // the database will not let two items claim the same place even for an instant.
    swapPositions(written.packId(), 0, 1);

    ContextPackEntity stored = packs.findById(written.packId()).orElseThrow();

    // The canonical order is by source first, so the pack was written as [b, a, c]:
    // BRAIN_ENTRY(30), CURRENT_TASK(60), ACTIVE_ERRORS(100). After the swap the entity view
    // faithfully reports [a, b, c] — @OrderBy is load-bearing — which is exactly why the domain
    // view must not quietly sort it back and disagree with it.
    assertThat(stored.getItems())
        .extracting(ContextPackItemEntity::getItemId)
        .containsExactly("tamper-a", "tamper-b", "tamper-c");

    assertThatThrownBy(stored::toDomain)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is not in canonical order")
        .hasMessageContaining("item_position values were altered")
        .hasMessageContaining("CANONICAL_ORDER has changed");
  }

  @Test
  @DisplayName("The database rejects an item that claims a place before the beginning")
  void negativePositionIsRejected() {
    ContextPack written =
        packOf(List.of(item("only", ContextKind.NOTE, ContextSourceType.PROJECT, "project-1", null)));
    packs.save(ContextPackEntity.from(compiled(written)));

    // Uniqueness alone would have accepted this row: no other item claims position -1.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    // Every column is supplied, the admission pair included, so the only thing
                    // this row violates is the position check. Leaving policy_rule_id or
                    // explanation out would still throw - on NOT NULL - and the test would pass
                    // while asserting nothing about item_position at all.
                    "INSERT INTO context_pack_items (id, pack_id, item_position, item_id, kind, "
                        + "label, content, source_type, source_id, source_version, "
                        + "provenance_project_id, recorded_at, policy_rule_id, explanation) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    written.packId(),
                    -1,
                    "before-the-beginning",
                    ContextKind.NOTE.name(),
                    "Synthetic label",
                    "Synthetic content",
                    ContextSourceType.PROJECT.name(),
                    "project-1",
                    null,
                    projectId,
                    OBSERVED_AT,
                    FIXTURE_ADMISSION.policyRuleId(),
                    FIXTURE_ADMISSION.explanation()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Human text longer than 200 characters persists: task reference and label are 500")
  void longHumanTextPersists() {
    // The realistic shape of the overflow: a reference decorated with a task title that is itself
    // allowed to be 200 characters long.
    String longTaskReference = "TASK-42: " + "t".repeat(200);
    String longLabel = "l".repeat(400);
    assertThat(longTaskReference.length()).isGreaterThan(200);

    ContextSource source = new ContextSource(ContextSourceType.CURRENT_TASK, "task-1", null);
    ContextItem labelled =
        new ContextItem(
            "long-label",
            ContextKind.OBJECTIVE,
            longLabel,
            "Synthetic content",
            new ContextProvenance(source, projectId, OBSERVED_AT));
    ContextPack written =
        new ContextPack(
            UUID.randomUUID(),
            projectId,
            longTaskReference,
            ASSEMBLED_AT,
            BUDGET,
            List.of(labelled));

    packs.save(ContextPackEntity.from(compiled(written)));

    ContextPack read = packs.findById(written.packId()).orElseThrow().toDomain();
    assertThat(read.taskReference()).isEqualTo(longTaskReference);
    assertThat(itemNamed(read, "long-label").label()).isEqualTo(longLabel);
  }

  @Test
  @DisplayName("Timestamps come back at microsecond precision, and this test says so out loud")
  void subSecondPrecisionIsMicroseconds() {
    // Deliberately not rounded off in the fixture. Both engines this project runs on store
    // TIMESTAMP WITH TIME ZONE at microsecond resolution, so the last three digits of a
    // nanosecond Instant do not survive the write. The behaviour is recorded here rather than
    // avoided, because the other tests use whole seconds and would never reveal it.
    //
    // Truncating at the entity boundary was considered and rejected: it would not make write and
    // read agree — the in-memory Instant still carries nanoseconds either way — it would only
    // replace the database's rounding with our own silent truncation, which is a transformation
    // someone would later have to discover. The schema is unchanged; the loss is documented.
    Instant nanosecondPrecision = Instant.parse("2026-03-01T10:15:30.123456789Z");
    ContextSource source = new ContextSource(ContextSourceType.BRAIN_ENTRY, "entry-1", 1);
    ContextItem precise =
        new ContextItem(
            "precise",
            ContextKind.DECISION,
            "Label for precise",
            "Synthetic content for precise",
            new ContextProvenance(source, projectId, nanosecondPrecision));
    ContextPack written =
        new ContextPack(
            UUID.randomUUID(), projectId, "TASK-42", nanosecondPrecision, BUDGET, List.of(precise));

    packs.save(ContextPackEntity.from(compiled(written)));
    ContextPack read = packs.findById(written.packId()).orElseThrow().toDomain();

    Instant readAssembledAt = read.assembledAt();
    Instant readRecordedAt = itemNamed(read, "precise").provenance().recordedAt();

    // What is kept: the second, and every digit down to the microsecond.
    assertThat(readAssembledAt.truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(nanosecondPrecision.truncatedTo(ChronoUnit.SECONDS));

    // What is lost: the nanosecond remainder. Asserted as "no sub-microsecond digits survive" and
    // "within one microsecond of what was written" rather than as an exact value, because the
    // engines disagree on how they discard it — H2 in PostgreSQL mode and PostgreSQL 16 both round
    // .123456789 up to .123457, while a truncating engine would give .123456. Nothing downstream
    // should depend on which, so this test does not either.
    assertThat(readAssembledAt.getNano() % 1_000).isZero();
    assertThat(readRecordedAt.getNano() % 1_000).isZero();
    assertThat(Duration.between(nanosecondPrecision, readAssembledAt).abs())
        .isLessThan(Duration.ofNanos(1_000));
    assertThat(Duration.between(nanosecondPrecision, readRecordedAt).abs())
        .isLessThan(Duration.ofNanos(1_000));

    // And so a nanosecond-precision instant does NOT round trip. Stated as an assertion so that a
    // future engine or column type that does preserve it fails this test and gets read.
    assertThat(readAssembledAt).isNotEqualTo(nanosecondPrecision);
  }

  /** Swaps two stored positions through a spare one, since no two items may share a place. */
  private void swapPositions(UUID packId, int first, int second) {
    int parking = 1_000;
    jdbc.update(
        "UPDATE context_pack_items SET item_position = ? WHERE pack_id = ? AND item_position = ?",
        parking,
        packId,
        first);
    jdbc.update(
        "UPDATE context_pack_items SET item_position = ? WHERE pack_id = ? AND item_position = ?",
        first,
        packId,
        second);
    jdbc.update(
        "UPDATE context_pack_items SET item_position = ? WHERE pack_id = ? AND item_position = ?",
        second,
        packId,
        parking);
  }

  private ContextItem itemNamed(ContextPack pack, String id) {
    return pack.items().stream()
        .filter(item -> item.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No item " + id + " in the pack read back"));
  }
}
