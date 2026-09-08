package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.context.application.source.ContextReadWindow;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.context.infrastructure.persistence.ContextPackRepository;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
      // Null-checked before it is stringified. String.valueOf(null) is the four characters "null",
      // which isNotBlank() accepts, so the obvious one-liner would pass on a null column. V9
      // declares source_id NOT NULL so this cannot happen today; the assertion is written to be
      // right rather than to be right by accident.
      assertThat(row.get("source_id")).as("source id column of an item row").isNotNull();
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
  @DisplayName("Every collector's source id resolves to a row of that source's own kind")
  void everySourceIdResolvesToTheRecordItNames() {
    // Read at the candidate level rather than off a compiled pack, deliberately. A pack holds only
    // what policy admitted, and policy admits nine of the eleven source types in this fixture -- a
    // check driven by admitted items alone would leave ROADMAP unverified forever and would go
    // quiet on any type a future policy change stopped admitting. Every collector's output is
    // resolved here; the admitted set is resolved again below, so both surfaces are covered.
    List<ContextItem> candidateItems =
        candidates.collect(planted.projectId(), ContextReadWindow.DEFAULT);
    assertThat(candidateItems).as("nothing was collected, so nothing was resolved").isNotEmpty();

    Set<ContextSourceType> resolvedTypes = EnumSet.noneOf(ContextSourceType.class);
    for (ContextItem item : candidateItems) {
      resolveOrFail(item);
      resolvedTypes.add(item.provenance().sourceType());
    }

    CompiledContextPack compiled =
        assembler.assemble(planted.projectId(), "CTX-09 resolution", GENEROUS);
    assertThat(compiled.admittedItems()).isNotEmpty();
    for (AdmittedContextItem admitted : compiled.admittedItems()) {
      resolveOrFail(admitted.item());
    }

    assertThat(resolvedTypes)
        .as(
            "every source type the engine can produce must have been resolved against a real row."
                + " A type missing here means a collector stopped producing items and this check"
                + " went quiet about it, which is the failure mode the whole test exists to avoid.")
        .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ContextSourceType.class));
  }

  @Test
  @DisplayName("A blocked task's active-error item resolves too, which the base fixture cannot show")
  void theTaskShapedActiveErrorResolvesAsWell() {
    // The base fixture produces the analysis-shaped ACTIVE_ERRORS item but never the task-shaped
    // one, because it blocks no task. Both shapes name different tables under the same source
    // type, so the second one is exercised here rather than left to a reader's assumption.
    Task blocked =
        tasks.addTask(
            planted.projectId(),
            planted.phase().getId(),
            2,
            "A task that gets stuck",
            "Blocked so that the task-shaped active error exists at all.",
            RiskLevel.LOW);
    tasks.start(planted.projectId(), blocked.getId());
    tasks.markBlocked(planted.projectId(), blocked.getId());

    List<ContextItem> taskShaped =
        candidates.collect(planted.projectId(), ContextReadWindow.DEFAULT).stream()
            .filter(item -> item.id().startsWith("active-error:task:"))
            .toList();
    assertThat(taskShaped)
        .as("blocking a task must produce the task-shaped active error, or this test checks nothing")
        .isNotEmpty();
    for (ContextItem item : taskShaped) {
      resolveOrFail(item);
    }
  }

  @Test
  @DisplayName("The resolution queries are counting: an id that names nothing resolves to zero")
  void theResolutionCheckIsNotBlind() {
    // Without this, a query with a typo in its WHERE clause -- or one that counted the whole table
    // -- would report every item as resolvable and the check above would be a formality.
    //
    // A task is blocked first so that the active-error:task shape is among the candidates. The
    // base fixture blocks nothing, so without this that branch of resolutionQueryFor would be the
    // one query shape whose positive path is exercised and whose zero path never is.
    Task blocked =
        tasks.addTask(
            planted.projectId(),
            planted.phase().getId(),
            3,
            "A task that gets stuck, again",
            "Blocked so the task-shaped active error is among the shapes checked below.",
            RiskLevel.LOW);
    tasks.start(planted.projectId(), blocked.getId());
    tasks.markBlocked(planted.projectId(), blocked.getId());

    List<ContextItem> collected =
        candidates.collect(planted.projectId(), ContextReadWindow.DEFAULT);
    for (ContextItem item : collected) {
      Integer matching =
          jdbc.queryForObject(
              resolutionQueryFor(item), Integer.class, UUID.randomUUID(), planted.projectId());
      assertThat(matching)
          .as("the query behind %s must find nothing for an id that names no row", item.id())
          .isZero();
    }

    // Both ACTIVE_ERRORS shapes have to be in the loop above, or the branch this test was extended
    // for is still unmeasured.
    assertThat(collected)
        .as("the task-shaped active error must be among the shapes just checked")
        .anyMatch(item -> item.id().startsWith("active-error:task:"));
    assertThat(collected)
        .as("and so must the analysis-shaped one, which takes the other branch")
        .anyMatch(item -> item.id().startsWith("active-error:analysis:"));
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
  @DisplayName("Rewriting one record's text in place moves the digest, at unchanged size and shape")
  void theDigestFollowsTheTextAndNotJustTheCount() {
    // The sibling test above changes the state by ADDING a record, so the item count moves and the
    // digest moves with it. That is a weaker property than it reads as: a digest taken over item
    // lengths, or over labels alone, would still move. This one holds the item count, the row
    // count, the ids, the labels, the versions and the character length all fixed and changes
    // nothing but the text of one existing brain entry, so the only thing left that can move the
    // digest is the content itself.
    String before = "Deterministic body ALPHA_zqxw_610455 recorded exactly as written";
    String after = "Deterministic body OMEGA_zqxw_610455 recorded exactly as written";
    assertThat(after.length())
        .as("the two bodies must be the same length or this test proves nothing about content")
        .isEqualTo(before.length());

    BrainEntry rewritten =
        brain.add(
            planted.projectId(),
            com.vibecode.brain.domain.BrainEntryType.DECISION,
            "A decision whose body is about to be rewritten",
            before,
            "test");
    assertThat(rewritten.getId()).isNotNull();

    CompiledContextPack first = assembler.assemble(planted.projectId(), "CTX-09 in-place", GENEROUS);
    int brainRowsBefore = brainRowCount();

    int rowsChanged =
        jdbc.update(
            "UPDATE brain_entries SET content = ? WHERE id = ? AND project_id = ?",
            after,
            rewritten.getId(),
            planted.projectId());
    assertThat(rowsChanged).as("the rewrite must have hit exactly the row it named").isEqualTo(1);

    CompiledContextPack second =
        assembler.assemble(planted.projectId(), "CTX-09 in-place", GENEROUS);

    // Everything that is not the text is pinned, so the digest change below cannot be attributed
    // to anything else.
    assertThat(second.size()).as("the same items, in the same number").isEqualTo(first.size());
    assertThat(idsOf(second)).as("the same items, under the same ids").isEqualTo(idsOf(first));
    assertThat(labelsOf(second)).as("and under the same labels").isEqualTo(labelsOf(first));
    assertThat(second.canonicalPayload().value().length())
        .as("and at the same payload length, because the rewrite preserved the character count")
        .isEqualTo(first.canonicalPayload().value().length());
    assertThat(brainRowCount()).as("no row was added or removed").isEqualTo(brainRowsBefore);

    // The digest first, on its own, before anything is said about the payload. Stated in this
    // order so that a digest which stopped covering item text fails HERE, naming the digest,
    // rather than being pre-empted by a payload assertion that would send the reader to the
    // serialiser instead.
    assertThat(second.packDigest())
        .as(
            "the digest must follow the text. Everything else about this pack is pinned above --"
                + " same items, same ids, same labels, same row count, same character length --"
                + " so an unchanged digest means the digest is covering something other than what"
                + " the items actually say.")
        .isNotEqualTo(first.packDigest());
    assertThat(second.canonicalPayload())
        .as("and the payload it is taken over must have moved with it")
        .isNotEqualTo(first.canonicalPayload());
  }

  @Test
  @DisplayName("The canonical payload carries a known item's exact content, not a stand-in for it")
  void thePayloadIsTheTextItself() {
    // Separate from the digest test on purpose. The digest could move for the right reason while
    // the payload held something other than the text -- a hash, a length, a label -- and a reader
    // who was told the payload is "a verbatim copy of every item's content" would be wrong. This
    // asserts the whole content string, not a fragment of it, and the string is one no label in
    // this fixture carries, so nothing but the content field can satisfy it.
    String body = "Payload body VERBATIM_zqxw_884120 written once and read back whole";
    brain.add(
        planted.projectId(),
        com.vibecode.brain.domain.BrainEntryType.DECISION,
        "A decision whose title says nothing about its body",
        body,
        "test");

    CompiledContextPack compiled =
        assembler.assemble(planted.projectId(), "CTX-09 payload text", GENEROUS);

    assertThat(labelsOf(compiled))
        .as("no label may contain the body, or the assertion below could be satisfied by a label")
        .noneMatch(label -> label.contains("VERBATIM_zqxw_884120"));
    assertThat(compiled.admittedItems())
        .as("the item carrying the body must have been admitted, or this test checks nothing")
        .anyMatch(admitted -> admitted.item().content().equals(body));

    assertThat(compiled.canonicalPayload().value())
        .as("the payload is a verbatim copy of item content, which is what the digest covers")
        .contains(body);

    // Non-vacuous: a string of the same shape that no item carries is absent, so "contains" is
    // discriminating rather than being satisfied by any long string at all.
    assertThat(compiled.canonicalPayload().value())
        .as("and text no item carries is not in it")
        .doesNotContain("Payload body ABSENT_zqxw_884121 written once and read back whole");
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

  private static List<String> labelsOf(CompiledContextPack pack) {
    return pack.admittedItems().stream().map(admitted -> admitted.item().label()).toList();
  }

  private int brainRowCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM brain_entries WHERE project_id = ?",
        Integer.class,
        planted.projectId());
  }

  /**
   * Fails unless the item's source id names exactly one live row of the kind its source type
   * claims, inside the project the pack describes.
   */
  private void resolveOrFail(ContextItem item) {
    // Parsed here rather than inline so a source id that is not a key at all fails as a provenance
    // problem naming the item, rather than as a bare "Invalid UUID string" out of the JDK.
    UUID sourceId;
    try {
      sourceId = UUID.fromString(item.provenance().sourceId());
    } catch (IllegalArgumentException notAKey) {
      throw new AssertionError(
          "item "
              + item.id()
              + " names source "
              + item.provenance().sourceType()
              + " "
              + item.provenance().sourceId()
              + ", which is not the primary key of any record, so there is nothing to check the"
              + " item against",
          notAKey);
    }
    Integer matching =
        jdbc.queryForObject(
            resolutionQueryFor(item), Integer.class, sourceId, planted.projectId());
    assertThat(matching)
        .as(
            "item %s claims source %s %s; that must name exactly one row of this project",
            item.id(), item.provenance().sourceType(), item.provenance().sourceId())
        .isEqualTo(1);

    // "A row of the right kind exists" is weaker than it reads: point every brain item at the same
    // brain entry and the count above is 1 for all of them, right table, right project, wrong row
    // for all but one. Every collector in the module embeds the record's key in the item id it
    // constructs -- brain:<id>, roadmap:<id>, roadmap-phase:<id>, current-phase:<id>,
    // current-task:<id>:objective, criterion:<id>, evidence:<id>, analysis:<id>,
    // active-error:{task,analysis}:<id>, project:<id>:identity, state:<id>:progress,
    // security-summary:<id> -- so the item id is a second, independently constructed statement of
    // which record this came from, and the two must agree.
    //
    // What it does NOT catch, so nobody reads more into it: a collector that derives the item id
    // and the source id from the SAME wrong record stays consistent and passes both checks. For
    // PROJECT, CURRENT_STATE and SECURITY_SUMMARY it adds nothing either -- their source id is the
    // project id, which the resolution query already pins to the pack's own project. It is aimed
    // squarely at the case above: provenance drifting away from an id that stayed correct.
    assertThat(item.id())
        .as(
            "item %s says it came from %s, but its own id names a different record -- the two are"
                + " constructed separately by the collector and must agree",
            item.id(), item.provenance().sourceId())
        .contains(item.provenance().sourceId());
  }

  /**
   * The row a source type points at, scoped to the project every time.
   *
   * <p>Two source types are ambiguous on the type alone and are split on the item id, which is the
   * only thing that distinguishes them: {@code ROADMAP} names either the roadmap or one of its
   * phases, and {@code ACTIVE_ERRORS} names either a blocked task or a failing analysis. Every
   * query is project-scoped -- through a join where the table carries no {@code project_id} -- so
   * an id that exists under another owner does not count as resolved.
   *
   * <p>The switch is exhaustive over the enum with no default, so a source type added later will
   * not compile until it is filed here. Silently passing an unrecognised one would be exactly the
   * hole this method was written to close.
   */
  private static String resolutionQueryFor(ContextItem item) {
    String phaseQuery =
        "SELECT COUNT(*) FROM roadmap_phases p JOIN roadmaps r ON r.id = p.roadmap_id"
            + " WHERE p.id = ? AND r.project_id = ?";
    String analysisQuery =
        "SELECT COUNT(*) FROM output_analysis_records a JOIN task_evidence e"
            + " ON e.id = a.evidence_id WHERE a.id = ? AND e.project_id = ?";
    return switch (item.provenance().sourceType()) {
      // The computed state and the security summary have no row of their own; each names the
      // project it was computed for, so the project row is what has to exist.
      case PROJECT, CURRENT_STATE, SECURITY_SUMMARY ->
          "SELECT COUNT(*) FROM projects WHERE id = ? AND id = ?";
      case BRAIN_ENTRY -> "SELECT COUNT(*) FROM brain_entries WHERE id = ? AND project_id = ?";
      case ROADMAP ->
          item.id().startsWith("roadmap-phase:")
              ? phaseQuery
              : "SELECT COUNT(*) FROM roadmaps WHERE id = ? AND project_id = ?";
      case CURRENT_PHASE -> phaseQuery;
      case CURRENT_TASK -> "SELECT COUNT(*) FROM tasks WHERE id = ? AND project_id = ?";
      case ACCEPTANCE_CRITERIA ->
          "SELECT COUNT(*) FROM task_acceptance_criteria c JOIN tasks t ON t.id = c.task_id"
              + " WHERE c.id = ? AND t.project_id = ?";
      case LATEST_EVIDENCE -> "SELECT COUNT(*) FROM task_evidence WHERE id = ? AND project_id = ?";
      case LATEST_OUTPUT_ANALYSIS -> analysisQuery;
      case ACTIVE_ERRORS ->
          item.id().startsWith("active-error:task:")
              ? "SELECT COUNT(*) FROM tasks WHERE id = ? AND project_id = ?"
              : analysisQuery;
    };
  }
}
