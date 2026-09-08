package com.vibecode.context.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextPack;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.project.application.ProjectService;
import com.vibecode.support.TestIdentity;
import java.time.Instant;
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
    packs.save(ContextPackEntity.from(written));

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
    packs.save(ContextPackEntity.from(written));

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

    packs.save(ContextPackEntity.from(first));
    packs.save(ContextPackEntity.from(second));

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

  private ContextItem itemNamed(ContextPack pack, String id) {
    return pack.items().stream()
        .filter(item -> item.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No item " + id + " in the pack read back"));
  }
}
