package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.application.source.ContextReadWindow;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.context.infrastructure.persistence.ContextPackRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * No orphan items, and the same state twice gives the same pack.
 *
 * <p><b>Provenance.</b> Every item that reaches a pack has to say where it came from, and "says"
 * means the whole tuple: a source type, a source id, the project the source belongs to, a kind and
 * an observation time — plus a version wherever the underlying record has revisions. An item
 * without that is a claim about the project that nobody can check against the record it allegedly
 * came from, which is the exact failure the Project Brain exists to prevent.
 *
 * <p><b>Determinism.</b> Compared on the canonical payload and the digest, which is what those two
 * exist for. Comparing pack ids or assembly timestamps would compare a random UUID and a clock
 * reading and would fail on a correct engine, so nothing here does that — it asserts the opposite,
 * that those two do differ while the logical content does not.
 */
class ContextProvenanceAndDeterminismTest extends ContextProbeFixture {

  @Autowired ContextPackRepository packs;

  private Planted planted;

  @BeforeEach
  void plant() {
    planted = plantTheProbeEverywhere("provenance-owner");
  }

  @Test
  @DisplayName("Every admitted item carries a whole provenance, and none belongs to another project")
  void noItemReachesAPackWithoutSayingWhereItCameFrom() {
    CompiledContextPack compiled =
        assembler.assemble(planted.projectId(), "CTX-09 provenance", GENEROUS);
    assertThat(compiled.admittedItems()).isNotEmpty();

    for (AdmittedContextItem admitted : compiled.admittedItems()) {
      ContextItem item = admitted.item();
      ContextProvenance provenance = item.provenance();

      assertThat(item.id()).as("item id").isNotBlank();
      assertThat(item.kind()).as("kind of %s", item.id()).isNotNull();
      assertThat(item.label()).as("label of %s", item.id()).isNotBlank();
      assertThat(item.content()).as("content of %s", item.id()).isNotBlank();
      assertThat(provenance.sourceType()).as("source type of %s", item.id()).isNotNull();
      assertThat(provenance.sourceId()).as("source id of %s", item.id()).isNotBlank();
      assertThat(provenance.projectId())
          .as("item %s must be drawn from the project the pack describes", item.id())
          .isEqualTo(planted.projectId());
      assertThat(provenance.recordedAt())
          .as("observation time of %s", item.id())
          .isNotNull()
          .isBefore(Instant.now().plusSeconds(60));

      // Where the record has revisions, the item says which one it saw. A brain entry is the only
      // versioned source today, and an item from one that omitted its version would be a snapshot
      // that cannot be compared with the record it came from.
      if (provenance.sourceType() == ContextSourceType.BRAIN_ENTRY) {
        assertThat(provenance.sourceVersion())
            .as("brain entry %s must carry the version it was read at", item.id())
            .isPresent();
        assertThat(provenance.sourceVersion().orElseThrow()).isGreaterThanOrEqualTo(1);
      }
    }

    // And the same, read back off the rows rather than off the object in hand.
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT * FROM context_pack_items WHERE pack_id = ?", compiled.packId());
    assertThat(rows).hasSize(compiled.admittedItems().size());
    for (Map<String, Object> row : rows) {
      assertThat(row.get("source_type")).isNotNull();
      assertThat(String.valueOf(row.get("source_id"))).isNotBlank();
      assertThat(row.get("recorded_at")).isNotNull();
      assertThat(UUID.fromString(String.valueOf(row.get("provenance_project_id"))))
          .isEqualTo(planted.projectId());
    }
  }

  @Test
  @DisplayName("A brain entry's item points back at a brain row that actually exists")
  void aSourceIdResolvesToTheRecordItNames() {
    CompiledContextPack compiled =
        assembler.assemble(planted.projectId(), "CTX-09 traceable", GENEROUS);

    List<AdmittedContextItem> fromBrain =
        compiled.admittedItems().stream()
            .filter(admitted -> admitted.item().provenance().sourceType()
                == ContextSourceType.BRAIN_ENTRY)
            .toList();
    assertThat(fromBrain)
        .as("the fixture admitted brain entries, or this test checks nothing")
        .isNotEmpty();

    for (AdmittedContextItem admitted : fromBrain) {
      Integer matching =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM brain_entries WHERE id = ? AND project_id = ?",
              Integer.class,
              UUID.fromString(admitted.item().provenance().sourceId()),
              planted.projectId());
      assertThat(matching)
          .as("item %s names brain row %s", admitted.id(),
              admitted.item().provenance().sourceId())
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("Two compilations of unchanged state agree on payload and digest, and only on those")
  void thePayloadAndTheDigestAreTheThingsThatRepeat() {
    CompiledContextPack first =
        assembler.assemble(planted.projectId(), "CTX-09 determinism", GENEROUS);
    CompiledContextPack second =
        assembler.assemble(planted.projectId(), "CTX-09 determinism", GENEROUS);

    assertThat(second.canonicalPayload()).isEqualTo(first.canonicalPayload());
    assertThat(second.packDigest()).isEqualTo(first.packDigest());
    assertThat(second.pack().contentFingerprint()).isEqualTo(first.pack().contentFingerprint());
    assertThat(idsOf(second)).isEqualTo(idsOf(first));

    // The two things that must NOT repeat, stated so nobody later "fixes" determinism by making
    // identity a function of content.
    assertThat(second.packId()).isNotEqualTo(first.packId());
    Integer distinctPacks =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM context_packs WHERE project_id = ? AND pack_digest = ?",
            Integer.class,
            planted.projectId(),
            first.packDigest());
    assertThat(distinctPacks)
        .as("two rows share a digest and neither displaced the other")
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("A changed record changes the digest, so the digest is not a constant")
  void theDigestMovesWhenTheStateMoves() {
    CompiledContextPack before =
        assembler.assemble(planted.projectId(), "CTX-09 movement", GENEROUS);

    brain.add(
        planted.projectId(),
        com.vibecode.brain.domain.BrainEntryType.DECISION,
        "A decision taken after the first pack",
        "This entry did not exist when the first pack was compiled",
        "test");

    CompiledContextPack after =
        assembler.assemble(planted.projectId(), "CTX-09 movement", GENEROUS);

    assertThat(after.packDigest())
        .as("if this were equal, the equality asserted elsewhere would mean nothing")
        .isNotEqualTo(before.packDigest());
    assertThat(after.size()).isGreaterThan(before.size());
  }

  @Test
  @DisplayName("A pack read back out of the database digests to what it digested when written")
  void theRoundTripDoesNotDisturbTheDigest() {
    CompiledContextPack written =
        assembler.assemble(planted.projectId(), "CTX-09 round trip", GENEROUS);

    CompiledContextPack read =
        packs.findByIdAndProjectId(written.packId(), planted.projectId())
            .orElseThrow(() -> new AssertionError("the pack that was just written did not load"))
            .toCompiled();

    assertThat(read.packDigest()).isEqualTo(written.packDigest());
    assertThat(read.canonicalPayload()).isEqualTo(written.canonicalPayload());
    assertThat(idsOf(read)).isEqualTo(idsOf(written));

    String storedDigest =
        jdbc.queryForObject(
            "SELECT pack_digest FROM context_packs WHERE id = ?", String.class, written.packId());
    assertThat(storedDigest).isEqualTo(written.packDigest());
  }

  @Test
  @DisplayName("Collection order does not depend on the read window's effect on candidate order")
  void collectionItselfRepeats() {
    List<ContextItem> first = candidates.collect(planted.projectId(), ContextReadWindow.DEFAULT);
    List<ContextItem> second = candidates.collect(planted.projectId(), ContextReadWindow.DEFAULT);
    assertThat(second).isEqualTo(first);
    assertThat(first).as("an empty collection would make the equality trivial").isNotEmpty();
  }

  private static List<String> idsOf(CompiledContextPack pack) {
    return pack.admittedItems().stream().map(AdmittedContextItem::id).toList();
  }
}
