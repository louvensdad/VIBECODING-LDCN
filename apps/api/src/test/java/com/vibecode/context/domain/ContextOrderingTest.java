package com.vibecode.context.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the ordering contract key by key.
 *
 * <p>A suite that only checks "the items came out in the expected order" can pass with half the
 * comparator deleted, because one key usually decides everything in a hand-written fixture. Each
 * test here is built so that exactly one key decides the outcome and every other key would order the
 * pair the other way. Deleting that key from {@link ContextItem#CANONICAL_ORDER} must fail the
 * matching test and no other.
 */
class ContextOrderingTest {

  private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final Instant OBSERVED = Instant.parse("2026-01-01T00:00:00Z");
  private static final ContextBudget GENEROUS = new ContextBudget(50, 10_000L, 20_000L);

  private static ContextItem item(
      String id, ContextKind kind, ContextSourceType sourceType, String sourceId, String content) {
    return new ContextItem(
        id,
        kind,
        "synthetic " + id,
        content,
        new ContextProvenance(ContextSource.of(sourceType, sourceId), PROJECT, OBSERVED));
  }

  private static ContextPack pack(List<ContextItem> items) {
    return new ContextPack(
        UUID.fromString("00000000-0000-0000-0000-0000000000b2"),
        PROJECT,
        "TASK-CTX-01",
        Instant.parse("2026-02-02T12:00:00Z"),
        GENEROUS,
        items);
  }

  private static List<String> idsOf(List<ContextItem> items) {
    return pack(items).items().stream().map(ContextItem::id).collect(Collectors.toList());
  }

  @Test
  void sourceTypeDecidesEvenWhenEveryLaterKeyDisagrees() {
    // The rule sorts first on source rank (BRAIN_RULE 70 before CURRENT_TASK 100) while kind,
    // source id and item id would all put the task item first.
    ContextItem task = item("i-a", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "s-a", "objective");
    ContextItem rule = item("i-b", ContextKind.RULE, ContextSourceType.BRAIN_RULE, "s-b", "rule");

    assertThat(idsOf(List.of(task, rule))).containsExactly("i-b", "i-a");
    assertThat(idsOf(List.of(rule, task))).containsExactly("i-b", "i-a");
  }

  @Test
  void kindDecidesWhenOnlyTheKindDiffers() {
    // Same source type and same source id, so key 1 and key 3 are ties. Kind ranks OBJECTIVE (10)
    // before CONSTRAINT (20), and the item ids are chosen to order the other way.
    ContextItem objective =
        item("i-z", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "s-same", "what");
    ContextItem constraint =
        item("i-a", ContextKind.CONSTRAINT, ContextSourceType.CURRENT_TASK, "s-same", "within what");

    assertThat(ContextItem.CANONICAL_ORDER.compare(objective, constraint)).isNegative();
    assertThat(idsOf(List.of(constraint, objective))).containsExactly("i-z", "i-a");
    assertThat(idsOf(List.of(objective, constraint))).containsExactly("i-z", "i-a");
  }

  @Test
  void sourceIdDecidesWhenOnlyTheSourceIdDiffers() {
    // Same source type and same kind, so keys 1 and 2 are ties. Source id "s-a" sorts before "s-b",
    // and the item ids are chosen to order the other way.
    ContextItem fromA =
        item("i-z", ContextKind.DECISION, ContextSourceType.BRAIN_DECISION, "s-a", "first record");
    ContextItem fromB =
        item("i-a", ContextKind.DECISION, ContextSourceType.BRAIN_DECISION, "s-b", "second record");

    assertThat(ContextItem.CANONICAL_ORDER.compare(fromA, fromB)).isNegative();
    assertThat(idsOf(List.of(fromB, fromA))).containsExactly("i-z", "i-a");
    assertThat(idsOf(List.of(fromA, fromB))).containsExactly("i-z", "i-a");
  }

  @Test
  void itemIdDecidesWhenEveryEarlierKeyTiesAndEveryPermutationAgrees() {
    // These three tie on source type, kind and source id, so only the final key can separate them.
    // Sorting is stable, so without that key each permutation would keep its insertion order.
    ContextItem alpha =
        item("i-alpha", ContextKind.STATE, ContextSourceType.CURRENT_STATE, "s-same", "alpha text");
    ContextItem beta =
        item("i-beta", ContextKind.STATE, ContextSourceType.CURRENT_STATE, "s-same", "beta text");
    ContextItem gamma =
        item("i-gamma", ContextKind.STATE, ContextSourceType.CURRENT_STATE, "s-same", "gamma text");

    List<String> expected = List.of("i-alpha", "i-beta", "i-gamma");
    String expectedFingerprint = pack(List.of(alpha, beta, gamma)).contentFingerprint();

    for (List<ContextItem> permutation : permutations(List.of(alpha, beta, gamma))) {
      ContextPack built = pack(permutation);
      assertThat(built.items())
          .as("permutation %s", permutation.stream().map(ContextItem::id).toList())
          .extracting(ContextItem::id)
          .containsExactlyElementsOf(expected);
      assertThat(built.contentFingerprint()).isEqualTo(expectedFingerprint);
    }
  }

  @Test
  void everySourceTypeRankIsUniqueAndStrictlyIncreasingInDeclarationOrder() {
    int previous = Integer.MIN_VALUE;
    for (ContextSourceType type : ContextSourceType.values()) {
      assertThat(type.orderingRank())
          .as("rank of %s must exceed the rank of the constant before it", type)
          .isGreaterThan(previous);
      previous = type.orderingRank();
    }
    assertThat(
            Arrays.stream(ContextSourceType.values())
                .map(ContextSourceType::orderingRank)
                .distinct()
                .count())
        .isEqualTo(ContextSourceType.values().length);
  }

  @Test
  void everyKindRankIsUniqueAndStrictlyIncreasingInDeclarationOrder() {
    int previous = Integer.MIN_VALUE;
    for (ContextKind kind : ContextKind.values()) {
      assertThat(kind.orderingRank())
          .as("rank of %s must exceed the rank of the constant before it", kind)
          .isGreaterThan(previous);
      previous = kind.orderingRank();
    }
    assertThat(Arrays.stream(ContextKind.values()).map(ContextKind::orderingRank).distinct().count())
        .isEqualTo(ContextKind.values().length);
  }

  @Test
  void theSourceVocabularyIsExactlyTheApprovedFifteen() {
    // A sixteenth source is a decision for the architect, not a side effect of a collector needing
    // somewhere to put something. The same goes for quietly dropping one.
    Set<ContextSourceType> approved =
        EnumSet.of(
            ContextSourceType.PROJECT,
            ContextSourceType.CURRENT_STATE,
            ContextSourceType.BRAIN_VISION,
            ContextSourceType.BRAIN_REQUIREMENT,
            ContextSourceType.BRAIN_ARCHITECTURE,
            ContextSourceType.BRAIN_DECISION,
            ContextSourceType.BRAIN_RULE,
            ContextSourceType.ROADMAP,
            ContextSourceType.CURRENT_PHASE,
            ContextSourceType.CURRENT_TASK,
            ContextSourceType.ACCEPTANCE_CRITERIA,
            ContextSourceType.LATEST_EVIDENCE,
            ContextSourceType.LATEST_OUTPUT_ANALYSIS,
            ContextSourceType.ACTIVE_ERRORS,
            ContextSourceType.SECURITY_SUMMARY);

    assertThat(approved).hasSize(15);
    assertThat(EnumSet.allOf(ContextSourceType.class)).isEqualTo(approved);
  }

  @Test
  void theKindVocabularyIsExactlyTheDeclaredTen() {
    Set<ContextKind> declared =
        EnumSet.of(
            ContextKind.OBJECTIVE,
            ContextKind.CONSTRAINT,
            ContextKind.RULE,
            ContextKind.DECISION,
            ContextKind.REQUIREMENT,
            ContextKind.ARCHITECTURE,
            ContextKind.STATE,
            ContextKind.EVIDENCE,
            ContextKind.DEFECT,
            ContextKind.SECURITY_NOTE);

    assertThat(declared).hasSize(10);
    assertThat(EnumSet.allOf(ContextKind.class)).isEqualTo(declared);
  }

  @Test
  void anItemIsNotComparableSoNoSortedCollectionCanTreatIdAsIdentity() {
    // compareTo's last key would be the id, which is not the item's identity: two items sharing an
    // id and differing in content would compare equal and a TreeSet would drop one.
    assertThat(Comparable.class.isAssignableFrom(ContextItem.class)).isFalse();
  }

  private static List<List<ContextItem>> permutations(List<ContextItem> items) {
    if (items.size() <= 1) {
      return List.of(items);
    }
    List<List<ContextItem>> result = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      List<ContextItem> rest = new ArrayList<>(items);
      ContextItem head = rest.remove(i);
      for (List<ContextItem> tail : permutations(rest)) {
        List<ContextItem> permutation = new ArrayList<>();
        permutation.add(head);
        permutation.addAll(tail);
        result.add(permutation);
      }
    }
    return result;
  }
}
