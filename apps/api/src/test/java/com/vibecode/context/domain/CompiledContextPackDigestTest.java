package com.vibecode.context.domain;

import com.vibecode.context.application.redaction.ContextRedaction;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the pack digest promises, and what it deliberately does not.
 *
 * <p>The claim is narrow and these tests hold it to exactly its width: the same logical inputs
 * produce the same canonical payload and therefore the same digest, while the things that
 * legitimately differ between two compilations of unchanged state - the pack id, the assembly
 * instant - do not move it. The persisted row is never claimed to be byte-identical, because it
 * is not and cannot be.
 *
 * <p>Each test that asserts "different X, different digest" exists because dropping X from the
 * payload is a change that would otherwise pass every other test in the suite. The rule id is the
 * one most worth guarding: a policy edit that swapped which rule admits an item would leave no
 * trace anywhere if the digest did not cover it.
 */
class CompiledContextPackDigestTest {

  private static final UUID PROJECT = UUID.randomUUID();
  private static final Instant OBSERVED_AT = Instant.parse("2026-03-01T10:15:30Z");
  private static final Instant ASSEMBLED_AT = Instant.parse("2026-03-01T10:16:00Z");
  private static final ContextBudget BUDGET = new ContextBudget(50, 100_000L, 200_000L);

  private static final ContextAdmission TASK_RULE =
      ContextAdmission.allow("context.policy.current-task", "The task this context is for.");

  @Test
  @DisplayName("The same logical inputs produce the same canonical payload, and the same digest")
  void sameInputsSamePayloadAndDigest() {
    List<AdmittedContextItem> items = List.of(admitted("a", TASK_RULE), admitted("b", TASK_RULE));

    // Deliberately different in exactly the two ways a rebuild always differs.
    CompiledContextPack first = pack(UUID.randomUUID(), ASSEMBLED_AT, version("1"), BUDGET, items);
    CompiledContextPack second =
        pack(UUID.randomUUID(), ASSEMBLED_AT.plusSeconds(3_600), version("1"), BUDGET, items);

    assertThat(first.packId()).isNotEqualTo(second.packId());
    assertThat(first.assembledAt()).isNotEqualTo(second.assembledAt());

    assertThat(first.canonicalPayload()).isEqualTo(second.canonicalPayload());
    assertThat(first.canonicalPayload().value()).isEqualTo(second.canonicalPayload().value());
    assertThat(first.packDigest()).isEqualTo(second.packDigest());
  }

  @Test
  @DisplayName("The order items are supplied in does not reach the digest")
  void inputOrderDoesNotMatter() {
    List<AdmittedContextItem> items =
        new ArrayList<>(
            List.of(
                admitted("a", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-9", TASK_RULE),
                admitted("b", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-1", TASK_RULE),
                admitted("c", ContextKind.ERROR, ContextSourceType.ACTIVE_ERRORS, "err-1", TASK_RULE)));

    CompiledContextPack asGiven = pack(UUID.randomUUID(), ASSEMBLED_AT, version("1"), BUDGET, items);

    List<AdmittedContextItem> shuffled = new ArrayList<>(items);
    java.util.Collections.shuffle(shuffled, new Random(20260301L));
    CompiledContextPack reordered =
        pack(UUID.randomUUID(), ASSEMBLED_AT, version("1"), BUDGET, shuffled);

    assertThat(reordered.packDigest()).isEqualTo(asGiven.packDigest());
    assertThat(reordered.admittedItems())
        .extracting(AdmittedContextItem::id)
        .containsExactlyElementsOf(asGiven.admittedItems().stream().map(AdmittedContextItem::id).toList());
  }

  @Test
  @DisplayName("Different included content produces a different digest")
  void contentReachesTheDigest() {
    CompiledContextPack original =
        pack(
            UUID.randomUUID(),
            ASSEMBLED_AT,
            version("1"),
            BUDGET,
            List.of(admitted("a", TASK_RULE)));

    ContextItem changed =
        new ContextItem(
            "a", ContextKind.OBJECTIVE, "Label for a", "Different content", provenance("task-1"));
    CompiledContextPack edited =
        pack(
            original.packId(),
            ASSEMBLED_AT,
            version("1"),
            BUDGET,
            List.of(new AdmittedContextItem(ContextRedaction.redact(changed), TASK_RULE)));

    assertThat(edited.packDigest()).isNotEqualTo(original.packDigest());
  }

  @Test
  @DisplayName("A different policy version produces a different digest, with everything else equal")
  void policyVersionReachesTheDigest() {
    List<AdmittedContextItem> items = List.of(admitted("a", TASK_RULE));

    CompiledContextPack underOne = pack(UUID.randomUUID(), ASSEMBLED_AT, version("1"), BUDGET, items);
    CompiledContextPack underTwo = pack(UUID.randomUUID(), ASSEMBLED_AT, version("2"), BUDGET, items);

    // Identical items, identical order, identical text. Only the rules that admitted them differ,
    // and that is a difference a reader has to be able to see.
    assertThat(underTwo.pack().contentFingerprint()).isEqualTo(underOne.pack().contentFingerprint());
    assertThat(underTwo.packDigest()).isNotEqualTo(underOne.packDigest());
  }

  @Test
  @DisplayName("A different admitting rule id produces a different digest")
  void ruleIdReachesTheDigest() {
    ContextAdmission otherRule =
        ContextAdmission.allow("context.policy.standing-memory", "The task this context is for.");

    CompiledContextPack underTask =
        pack(UUID.randomUUID(), ASSEMBLED_AT, version("1"), BUDGET, List.of(admitted("a", TASK_RULE)));
    CompiledContextPack underMemory =
        pack(UUID.randomUUID(), ASSEMBLED_AT, version("1"), BUDGET, List.of(admitted("a", otherRule)));

    // The explanation is identical in both, so only the id differs. Dropping the id from the
    // payload would make these two packs indistinguishable, and a policy change that quietly moved
    // an item from one rule to another would leave nothing behind.
    assertThat(underMemory.packDigest()).isNotEqualTo(underTask.packDigest());
  }

  @Test
  @DisplayName("A different explanation produces a different digest")
  void explanationReachesTheDigest() {
    ContextAdmission reworded =
        ContextAdmission.allow("context.policy.current-task", "A differently worded reason.");

    CompiledContextPack original =
        pack(UUID.randomUUID(), ASSEMBLED_AT, version("1"), BUDGET, List.of(admitted("a", TASK_RULE)));
    CompiledContextPack changed =
        pack(UUID.randomUUID(), ASSEMBLED_AT, version("1"), BUDGET, List.of(admitted("a", reworded)));

    assertThat(changed.packDigest()).isNotEqualTo(original.packDigest());
  }

  @Test
  @DisplayName("A different budget produces a different digest")
  void budgetReachesTheDigest() {
    List<AdmittedContextItem> items = List.of(admitted("a", TASK_RULE));

    CompiledContextPack generous =
        pack(UUID.randomUUID(), ASSEMBLED_AT, version("1"), BUDGET, items);
    CompiledContextPack tight =
        pack(
            UUID.randomUUID(),
            ASSEMBLED_AT,
            version("1"),
            new ContextBudget(50, 100_000L, 199_999L),
            items);

    // The same items can be selected under two different ceilings, but they were not answers to
    // the same question, and two packs that answered different questions are not the same pack.
    assertThat(tight.packDigest()).isNotEqualTo(generous.packDigest());
  }

  @Test
  @DisplayName("Two packs whose fields differ only in where the boundary falls do not share a digest")
  void lengthPrefixesMakeTheEncodingUnambiguous() {
    // A real collision, not two items that merely differ. The label and the content of the two
    // packs below concatenate to exactly the same characters, separator included; the only
    // difference is which side of the label/content boundary the middle piece sits on. Under an
    // encoding that relied on the separator alone, both would flatten to the same string and share
    // a digest - which is the whole reason every field is written with its length in front.
    //
    // An earlier version of this test compared a "forging" item against a plain one whose text was
    // different outright. That passes under any encoding at all, so it guarded nothing: the
    // separator-only version of appendField sails through it.
    String unitSeparator = String.valueOf((char) 0x1F);

    String labelLeft = "a";
    String contentLeft = "b" + unitSeparator + "cd";
    String labelRight = "a" + unitSeparator + "b";
    String contentRight = "cd";

    // The premise, asserted rather than assumed. Under an encoding that wrote each field followed
    // by a separator, these two label/content pairs flatten to the identical string - which is
    // exactly what makes this a collision rather than two items that merely differ. If a future
    // edit breaks this, the test below stops guarding anything and starts being trivially true.
    assertThat(labelLeft + unitSeparator + contentLeft)
        .as("the two packs must flatten identically without the length prefixes")
        .isEqualTo(labelRight + unitSeparator + contentRight);
    assertThat(labelLeft).isNotEqualTo(labelRight);

    CompiledContextPack left = packOfOneItem(labelLeft, contentLeft);
    CompiledContextPack right = packOfOneItem(labelRight, contentRight);

    assertThat(left.packDigest()).isNotEqualTo(right.packDigest());
    assertThat(left.packDigest()).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  @DisplayName("A different task reference produces a different digest")
  void taskReferenceReachesTheDigest() {
    List<AdmittedContextItem> items = List.of(admitted("a", TASK_RULE));

    CompiledContextPack forTask42 =
        new CompiledContextPack(
            UUID.randomUUID(), PROJECT, "TASK-42", ASSEMBLED_AT, BUDGET, version("1"), items);
    CompiledContextPack forTask43 =
        new CompiledContextPack(
            UUID.randomUUID(), PROJECT, "TASK-43", ASSEMBLED_AT, BUDGET, version("1"), items);

    // The same items can be assembled for two different tasks, and they are not the same pack:
    // the task is part of what "the same inputs" means.
    assertThat(forTask43.packDigest()).isNotEqualTo(forTask42.packDigest());
  }

  @Test
  @DisplayName("A different project produces a different digest")
  void projectIdReachesTheDigest() {
    // Two projects whose records happen to say the same things. Sharing a digest would let a
    // comparison across projects read as "these are the same context", which is a claim about
    // somebody else's data.
    CompiledContextPack here =
        new CompiledContextPack(
            UUID.randomUUID(),
            PROJECT,
            "TASK-42",
            ASSEMBLED_AT,
            BUDGET,
            version("1"),
            List.of(admittedIn(PROJECT, "a", TASK_RULE)));

    UUID elsewhere = UUID.randomUUID();
    CompiledContextPack there =
        new CompiledContextPack(
            UUID.randomUUID(),
            elsewhere,
            "TASK-42",
            ASSEMBLED_AT,
            BUDGET,
            version("1"),
            List.of(admittedIn(elsewhere, "a", TASK_RULE)));

    assertThat(there.packDigest()).isNotEqualTo(here.packDigest());
  }

  /** One pack holding one item with the given label and content, everything else fixed. */
  private static CompiledContextPack packOfOneItem(String label, String content) {
    ContextItem item =
        new ContextItem("a", ContextKind.OBJECTIVE, label, content, provenance("task-1"));
    return pack(
        UUID.randomUUID(),
        ASSEMBLED_AT,
        version("1"),
        BUDGET,
        List.of(new AdmittedContextItem(ContextRedaction.redact(item), TASK_RULE)));
  }

  @Test
  @DisplayName("The pack digest and the content fingerprint are different numbers, and neither is identity")
  void theTwoDigestsAreNotInterchangeable() {
    List<AdmittedContextItem> items = List.of(admitted("a", TASK_RULE));

    CompiledContextPack first = pack(UUID.randomUUID(), ASSEMBLED_AT, version("1"), BUDGET, items);
    CompiledContextPack second =
        pack(UUID.randomUUID(), ASSEMBLED_AT.plusSeconds(600), version("1"), BUDGET, items);

    assertThat(first.packDigest()).isNotEqualTo(first.pack().contentFingerprint());

    // Two distinct packs share both digests. Either one used as a key would collapse them into
    // one row and lose a snapshot; identity is the pack id, and it differs.
    assertThat(second.packDigest()).isEqualTo(first.packDigest());
    assertThat(second.pack().contentFingerprint()).isEqualTo(first.pack().contentFingerprint());
    assertThat(second.packId()).isNotEqualTo(first.packId());
  }

  private static ContextPolicyVersion version(String value) {
    return new ContextPolicyVersion(value);
  }

  private static CompiledContextPack pack(
      UUID packId,
      Instant assembledAt,
      ContextPolicyVersion policyVersion,
      ContextBudget budget,
      List<AdmittedContextItem> items) {
    return new CompiledContextPack(
        packId, PROJECT, "TASK-42", assembledAt, budget, policyVersion, items);
  }

  private static AdmittedContextItem admittedIn(
      UUID projectId, String id, ContextAdmission admission) {
    ContextItem item =
        new ContextItem(
            id,
            ContextKind.OBJECTIVE,
            "Label for " + id,
            "Synthetic content for " + id,
            new ContextProvenance(
                ContextSource.of(ContextSourceType.CURRENT_TASK, "task-1"), projectId, OBSERVED_AT));
    return new AdmittedContextItem(ContextRedaction.redact(item), admission);
  }

  private static AdmittedContextItem admitted(String id, ContextAdmission admission) {
    return admitted(id, ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-1", admission);
  }

  private static AdmittedContextItem admitted(
      String id,
      ContextKind kind,
      ContextSourceType sourceType,
      String sourceId,
      ContextAdmission admission) {
    ContextItem item =
        new ContextItem(
            id,
            kind,
            "Label for " + id,
            "Synthetic content for " + id,
            new ContextProvenance(
                ContextSource.of(sourceType, sourceId), PROJECT, OBSERVED_AT));
    return new AdmittedContextItem(ContextRedaction.redact(item), admission);
  }

  private static ContextProvenance provenance(String sourceId) {
    return new ContextProvenance(
        ContextSource.of(ContextSourceType.CURRENT_TASK, sourceId), PROJECT, OBSERVED_AT);
  }
}
