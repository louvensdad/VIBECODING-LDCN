package com.vibecode.context.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextPackTest {

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

  private static ContextPack pack(ContextBudget budget, List<ContextItem> items) {
    return new ContextPack(
        UUID.fromString("00000000-0000-0000-0000-0000000000b2"),
        PROJECT,
        "TASK-CTX-01",
        Instant.parse("2026-02-02T12:00:00Z"),
        budget,
        items);
  }

  private static List<ContextItem> sampleItems() {
    List<ContextItem> items = new ArrayList<>();
    items.add(item("i-evidence", ContextKind.EVIDENCE, ContextSourceType.LATEST_EVIDENCE, "ev-1", "green"));
    items.add(item("i-rule", ContextKind.RULE, ContextSourceType.BRAIN_RULE, "rule-9", "no dumps"));
    items.add(item("i-task", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-3", "model the domain"));
    items.add(item("i-decision", ContextKind.DECISION, ContextSourceType.BRAIN_DECISION, "dec-2", "modular monolith"));
    return items;
  }

  @Test
  void twoIndependentlyBuiltPacksAgreeOnOrderAndContent() {
    List<ContextItem> forward = sampleItems();
    List<ContextItem> shuffled = new ArrayList<>(sampleItems());
    java.util.Collections.reverse(shuffled);

    ContextPack first = pack(GENEROUS, forward);
    ContextPack second = pack(GENEROUS, shuffled);

    assertThat(first.items()).containsExactlyElementsOf(second.items());
    assertThat(first.contentFingerprint()).isEqualTo(second.contentFingerprint());
  }

  @Test
  void orderingFollowsSourceThenKindThenSourceIdThenItemId() {
    ContextPack built = pack(GENEROUS, sampleItems());

    assertThat(built.items())
        .extracting(ContextItem::id)
        .containsExactly("i-decision", "i-rule", "i-task", "i-evidence");
  }

  @Test
  void itemIdBreaksAnyRemainingTieSoTheOrderIsTotal() {
    ContextItem b =
        item("i-b", ContextKind.STATE, ContextSourceType.CURRENT_STATE, "state-1", "beta");
    ContextItem a =
        item("i-a", ContextKind.STATE, ContextSourceType.CURRENT_STATE, "state-1", "alpha");

    assertThat(ContextItem.CANONICAL_ORDER.compare(a, b)).isNegative();
    assertThat(ContextItem.CANONICAL_ORDER.compare(b, a)).isPositive();
    assertThat(pack(GENEROUS, List.of(b, a)).items()).extracting(ContextItem::id).containsExactly("i-a", "i-b");
  }

  @Test
  void aReturnedItemListCannotBeUsedToMutateThePack() {
    ContextPack built = pack(GENEROUS, sampleItems());
    ContextItem intruder =
        item("i-intruder", ContextKind.STATE, ContextSourceType.CURRENT_STATE, "state-x", "sneaked in");

    assertThatThrownBy(() -> built.items().add(intruder))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(built.size()).isEqualTo(4);
  }

  @Test
  void mutatingTheListHandedToTheConstructorDoesNotChangeThePack() {
    List<ContextItem> caller = sampleItems();
    ContextPack built = pack(GENEROUS, caller);

    caller.clear();

    assertThat(built.size()).isEqualTo(4);
  }

  @Test
  void budgetAccountingIsExactForItemsCharactersAndBytes() {
    // "eur" costs three bytes in UTF-8 and one character, so the two dimensions must not agree.
    String multiByte = "cout: 3€";
    ContextItem ascii =
        item("i-ascii", ContextKind.STATE, ContextSourceType.CURRENT_STATE, "state-1", "abcde");
    ContextItem wide =
        item("i-wide", ContextKind.STATE, ContextSourceType.CURRENT_STATE, "state-2", multiByte);

    ContextUsage usage = pack(GENEROUS, List.of(ascii, wide)).usage();

    assertThat(usage.items()).isEqualTo(2);
    assertThat(usage.characters()).isEqualTo(5L + multiByte.length());
    assertThat(usage.bytes()).isEqualTo(5L + multiByte.length() + 2L);
    assertThat(usage.characters()).isNotEqualTo(usage.bytes());
  }

  @Test
  void aPackThatDoesNotFitItsOwnBudgetCannotBeBuilt() {
    ContextBudget oneItem = new ContextBudget(1, 10_000L, 20_000L);

    assertThatThrownBy(() -> pack(oneItem, sampleItems()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("items: 4 > 1");

    ContextBudget tightCharacters = new ContextBudget(50, 4L, 20_000L);
    assertThatThrownBy(() -> pack(tightCharacters, sampleItems()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("characters:");
  }

  @Test
  void twoItemsCannotShareAnIdBecauseTheIdIsTheFinalOrderingKey() {
    ContextItem one =
        item("i-same", ContextKind.STATE, ContextSourceType.CURRENT_STATE, "state-1", "first");
    ContextItem two =
        item("i-same", ContextKind.STATE, ContextSourceType.CURRENT_STATE, "state-2", "second");

    assertThatThrownBy(() -> pack(GENEROUS, List.of(one, two)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate item id");
  }

  @Test
  void anItemFromAnotherProjectIsRefused() {
    ContextItem foreign =
        new ContextItem(
            "i-foreign",
            ContextKind.STATE,
            "synthetic foreign",
            "belongs elsewhere",
            new ContextProvenance(
                ContextSource.of(ContextSourceType.CURRENT_STATE, "state-1"),
                UUID.fromString("00000000-0000-0000-0000-0000000000ff"),
                OBSERVED));

    assertThatThrownBy(() -> pack(GENEROUS, List.of(foreign)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not the project this pack describes");
  }
}
