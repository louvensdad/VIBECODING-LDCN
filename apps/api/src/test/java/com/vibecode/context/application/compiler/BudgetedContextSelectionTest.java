package com.vibecode.context.application.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.application.redaction.ContextRedaction;
import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the budget binds, and what it is never allowed to do when it does.
 *
 * <p>Two properties are worth more than the arithmetic. First, nothing is ever cut in half: every
 * item that comes out is byte-for-byte the item that went in. Second, the result does not depend
 * on the order the caller happened to supply items in, because a pack that changed with the wind
 * would make every determinism claim downstream meaningless.
 *
 * <p>The Unicode cases are here because "characters" is a word with two plausible meanings and the
 * domain already picked one: UTF-16 code units, in which an emoji counts 2. A budget measured in
 * code points against a domain measuring code units would be wrong by an amount nobody notices
 * until a pack full of non-Latin text silently overruns.
 */
class BudgetedContextSelectionTest {

  private static final UUID PROJECT = UUID.randomUUID();
  private static final Instant OBSERVED_AT = Instant.parse("2026-03-01T10:15:30Z");
  private static final ContextAdmission ADMISSION =
      ContextAdmission.allow("test.fixture.selection", "A synthetic admission for the fixtures.");

  @Test
  @DisplayName("maxItems is respected, and the items kept are the first in canonical order")
  void maxItemsIsRespected() {
    List<AdmittedContextItem> items =
        List.of(
            item("brain", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1", "aaa"),
            item("task", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-1", "bbb"),
            item("error", ContextKind.ERROR, ContextSourceType.ACTIVE_ERRORS, "err-1", "ccc"));

    List<AdmittedContextItem> selected =
        BudgetedContextSelection.select(items, new ContextBudget(2, 1_000L, 1_000L));

    // BRAIN_ENTRY ranks 30, CURRENT_TASK 60, ACTIVE_ERRORS 100 - so the error is the one left out,
    // by position in the canonical order and not by any judgement about its worth.
    assertThat(selected).extracting(AdmittedContextItem::id).containsExactly("brain", "task");
  }

  @Test
  @DisplayName("maxCharacters is respected, counted in UTF-16 code units over content alone")
  void maxCharactersIsRespected() {
    List<AdmittedContextItem> items =
        List.of(
            item("brain", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1", "x".repeat(10)),
            item("task", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-1", "y".repeat(10)));

    assertThat(BudgetedContextSelection.select(items, new ContextBudget(10, 20L, 1_000L)))
        .hasSize(2);
    assertThat(BudgetedContextSelection.select(items, new ContextBudget(10, 19L, 1_000L)))
        .extracting(AdmittedContextItem::id)
        .containsExactly("brain");
  }

  @Test
  @DisplayName("maxBytes is respected, counted in UTF-8, and is not the same limit as characters")
  void maxBytesIsRespected() {
    // Three-byte characters: ten of them are 10 code units and 30 bytes, so a budget that is
    // generous in characters and tight in bytes must still bind. The two dimensions are
    // independent and neither implies the other.
    String multibyte = "世".repeat(10);
    assertThat(multibyte.length()).isEqualTo(10);
    assertThat(multibyte.getBytes(StandardCharsets.UTF_8)).hasSize(30);

    List<AdmittedContextItem> items =
        List.of(
            item("brain", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1", multibyte),
            item("task", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-1", multibyte));

    assertThat(BudgetedContextSelection.select(items, new ContextBudget(10, 1_000L, 60L)))
        .hasSize(2);
    assertThat(BudgetedContextSelection.select(items, new ContextBudget(10, 1_000L, 59L)))
        .extracting(AdmittedContextItem::id)
        .containsExactly("brain");
  }

  @Test
  @DisplayName("An emoji counts two characters, as the domain says it does")
  void surrogatePairsCountAsTwoCharacters() {
    // One emoji: two UTF-16 code units, four UTF-8 bytes. Both budgets below are one short of
    // what the item costs in their own unit, so both must exclude it.
    String emoji = "😀";
    assertThat(emoji.length()).isEqualTo(2);
    assertThat(emoji.getBytes(StandardCharsets.UTF_8)).hasSize(4);

    List<AdmittedContextItem> only =
        List.of(item("e", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1", emoji));

    assertThat(BudgetedContextSelection.select(only, new ContextBudget(10, 2L, 4L))).hasSize(1);
    assertThat(BudgetedContextSelection.select(only, new ContextBudget(10, 1L, 4L))).isEmpty();
    assertThat(BudgetedContextSelection.select(only, new ContextBudget(10, 2L, 3L))).isEmpty();
  }

  @Test
  @DisplayName("An item that does not fit is skipped whole, and smaller items behind it still fit")
  void anOversizedItemIsSkippedAtTheItemBoundary() {
    List<AdmittedContextItem> items =
        List.of(
            item("brain", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1", "x".repeat(50)),
            item("task", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-1", "y".repeat(5)),
            item("error", ContextKind.ERROR, ContextSourceType.ACTIVE_ERRORS, "err-1", "z".repeat(5)));

    List<AdmittedContextItem> selected =
        BudgetedContextSelection.select(items, new ContextBudget(10, 20L, 1_000L));

    // The oversized first item costs only itself. Stopping at it instead would let one long error
    // message empty the rest of a pack, and what a pack contained would depend on the size of
    // something not in it.
    assertThat(selected).extracting(AdmittedContextItem::id).containsExactly("task", "error");
  }

  @Test
  @DisplayName("Nothing is ever truncated: every selected item is byte-identical to its candidate")
  void noItemIsEverCutInHalf() {
    String longContent = "sentence one. sentence two. sentence three.".repeat(20);
    List<AdmittedContextItem> items =
        List.of(
            item("brain", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1", longContent),
            item("task", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-1", "short"));

    // A ceiling that lands in the middle of the long item, which is exactly where a truncating
    // implementation would cut.
    List<AdmittedContextItem> selected =
        BudgetedContextSelection.select(items, new ContextBudget(10, longContent.length() / 2, 1_000_000L));

    for (AdmittedContextItem chosen : selected) {
      AdmittedContextItem original =
          items.stream().filter(candidate -> candidate.id().equals(chosen.id())).findFirst().orElseThrow();
      assertThat(chosen.item().content()).isEqualTo(original.item().content());
      assertThat(chosen.item()).isEqualTo(original.item());
    }
    assertThat(selected).extracting(AdmittedContextItem::id).containsExactly("task");
  }

  @Test
  @DisplayName("The result does not depend on the order items were handed over")
  void selectionIsIndependentOfInputOrder() {
    List<AdmittedContextItem> items =
        new ArrayList<>(
            List.of(
                item("brain", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1", "aaaa"),
                item("task", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-1", "bbbb"),
                item("crit", ContextKind.CONSTRAINT, ContextSourceType.ACCEPTANCE_CRITERIA, "c-1", "cccc"),
                item("error", ContextKind.ERROR, ContextSourceType.ACTIVE_ERRORS, "err-1", "dddd")));

    ContextBudget budget = new ContextBudget(3, 1_000L, 1_000L);
    List<String> expected =
        BudgetedContextSelection.select(items, budget).stream().map(AdmittedContextItem::id).toList();

    Random seed = new Random(20260301L);
    for (int attempt = 0; attempt < 20; attempt++) {
      java.util.Collections.shuffle(items, seed);
      assertThat(BudgetedContextSelection.select(items, budget))
          .extracting(AdmittedContextItem::id)
          .containsExactlyElementsOf(expected);
    }
  }

  @Test
  @DisplayName("A budget nothing fits under yields an empty selection rather than a partial item")
  void nothingFittingYieldsNothing() {
    List<AdmittedContextItem> items =
        List.of(item("brain", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "entry-1", "x".repeat(50)));

    assertThat(BudgetedContextSelection.select(items, new ContextBudget(1, 1L, 1L))).isEmpty();
  }

  private static AdmittedContextItem item(
      String id, ContextKind kind, ContextSourceType sourceType, String sourceId, String content) {
    ContextItem item =
        new ContextItem(
            id,
            kind,
            "Label for " + id,
            content,
            new ContextProvenance(ContextSource.of(sourceType, sourceId), PROJECT, OBSERVED_AT));
    // Through the real redaction step rather than around it: an AdmittedContextItem no longer
    // accepts a bare ContextItem, and a test helper that minted the wrapper itself would be
    // demonstrating a route that production code does not have.
    return new AdmittedContextItem(ContextRedaction.redact(item), ADMISSION);
  }
}
