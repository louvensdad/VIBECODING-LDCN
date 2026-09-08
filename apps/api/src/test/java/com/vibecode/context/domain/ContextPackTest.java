package com.vibecode.context.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.context.application.redaction.ContextRedaction;
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
    items.add(item("i-rule", ContextKind.RULE, ContextSourceType.BRAIN_ENTRY, "rule-9", "no dumps"));
    items.add(item("i-task", ContextKind.OBJECTIVE, ContextSourceType.CURRENT_TASK, "task-3", "model the domain"));
    items.add(item("i-decision", ContextKind.DECISION, ContextSourceType.BRAIN_ENTRY, "dec-2", "modular monolith"));
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
        item("i-b", ContextKind.CURRENT_STATE, ContextSourceType.CURRENT_STATE, "state-1", "beta");
    ContextItem a =
        item("i-a", ContextKind.CURRENT_STATE, ContextSourceType.CURRENT_STATE, "state-1", "alpha");

    assertThat(ContextItem.CANONICAL_ORDER.compare(a, b)).isNegative();
    assertThat(ContextItem.CANONICAL_ORDER.compare(b, a)).isPositive();
    assertThat(pack(GENEROUS, List.of(b, a)).items()).extracting(ContextItem::id).containsExactly("i-a", "i-b");
  }

  @Test
  void aReturnedItemListCannotBeUsedToMutateThePack() {
    ContextPack built = pack(GENEROUS, sampleItems());
    ContextItem intruder =
        item("i-intruder", ContextKind.CURRENT_STATE, ContextSourceType.CURRENT_STATE, "state-x", "sneaked in");

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
        item("i-ascii", ContextKind.CURRENT_STATE, ContextSourceType.CURRENT_STATE, "state-1", "abcde");
    ContextItem wide =
        item("i-wide", ContextKind.CURRENT_STATE, ContextSourceType.CURRENT_STATE, "state-2", multiByte);

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
        item("i-same", ContextKind.CURRENT_STATE, ContextSourceType.CURRENT_STATE, "state-1", "first");
    ContextItem two =
        item("i-same", ContextKind.CURRENT_STATE, ContextSourceType.CURRENT_STATE, "state-2", "second");

    assertThatThrownBy(() -> pack(GENEROUS, List.of(one, two)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate item id");
  }

  @Test
  void anItemFromAnotherProjectIsRefused() {
    ContextItem foreign =
        new ContextItem(
            "i-foreign",
            ContextKind.CURRENT_STATE,
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

@Test
  void aFieldsOwnTextCannotForgeADigestBoundary() {
    // The separator is a character like any other and can appear inside content this domain does
    // not control. Shifting one across the label/content boundary produced the same flattened
    // string before every field was length-prefixed.
    String separator = String.valueOf((char) 0x1F);
    ContextItem shiftedLeft =
        new ContextItem(
            "i-1",
            ContextKind.CURRENT_STATE,
            "x",
            "1" + separator + "2",
            new ContextProvenance(
                ContextSource.of(ContextSourceType.CURRENT_STATE, "s-1"), PROJECT, OBSERVED));
    ContextItem shiftedRight =
        new ContextItem(
            "i-1",
            ContextKind.CURRENT_STATE,
            "x" + separator + "1",
            "2",
            new ContextProvenance(
                ContextSource.of(ContextSourceType.CURRENT_STATE, "s-1"), PROJECT, OBSERVED));

    assertThat(pack(GENEROUS, List.of(shiftedLeft)).contentFingerprint())
        .isNotEqualTo(pack(GENEROUS, List.of(shiftedRight)).contentFingerprint());
  }

  @Test
  void theDigestCoversTheLabelBecauseALabelIsDisplayedText() {
    ContextProvenance provenance =
        new ContextProvenance(
            ContextSource.of(ContextSourceType.CURRENT_STATE, "s-1"), PROJECT, OBSERVED);
    ContextItem shown =
        new ContextItem("i-1", ContextKind.CURRENT_STATE, "Current phase", "phase six", provenance);
    ContextItem relabelled =
        new ContextItem("i-1", ContextKind.CURRENT_STATE, "Something else", "phase six", provenance);

    assertThat(pack(GENEROUS, List.of(shown)).contentFingerprint())
        .isNotEqualTo(pack(GENEROUS, List.of(relabelled)).contentFingerprint());
  }

  @Test
  void theDigestIgnoresWhenTheSourceWasReadSoUnchangedStateKeepsItsDigest() {
    ContextSource source = ContextSource.of(ContextSourceType.CURRENT_STATE, "s-1");
    ContextItem readEarlier =
        new ContextItem(
            "i-1",
            ContextKind.CURRENT_STATE,
            "Current phase",
            "phase six",
            new ContextProvenance(source, PROJECT, OBSERVED));
    ContextItem readLater =
        new ContextItem(
            "i-1",
            ContextKind.CURRENT_STATE,
            "Current phase",
            "phase six",
            new ContextProvenance(source, PROJECT, OBSERVED.plusSeconds(3600)));

    assertThat(pack(GENEROUS, List.of(readEarlier)).contentFingerprint())
        .isEqualTo(pack(GENEROUS, List.of(readLater)).contentFingerprint());
  }

  @Test
  void charactersAreCountedInUtf16CodeUnitsNotUserVisibleCharacters() {
    // Stated so a later truncator measures in the same unit the budget is expressed in: one
    // grinning face is one code point, two code units and four UTF-8 bytes.
    String grinningFace = new String(Character.toChars(0x1F600));
    ContextItem emoji =
        item("i-emoji", ContextKind.CURRENT_STATE, ContextSourceType.CURRENT_STATE, "s-1", grinningFace);

    assertThat(grinningFace.codePointCount(0, grinningFace.length())).isEqualTo(1);
    assertThat(emoji.characterCount()).isEqualTo(2L);
    assertThat(emoji.byteCount()).isEqualTo(4L);
  }

  @Test
  void aByteCeilingBelowTheCharacterCeilingIsALegitimateConfiguration() {
    // Characters as a rough size guide, bytes as a transport limit. The byte limit simply binds
    // first, which is the point of setting it.
    ContextBudget transportBound = new ContextBudget(50, 4_000L, 2_000L);

    assertThat(transportBound.maxBytes()).isEqualTo(2_000L);
    assertThat(transportBound.admits(new ContextUsage(1, 3_000L, 3_000L))).isFalse();
    assertThat(transportBound.admits(new ContextUsage(1, 1_500L, 1_500L))).isTrue();
  }

  @Test
  void firstBreachIsEmptyWhenTheUsageFits() {
    assertThat(GENEROUS.firstBreach(new ContextUsage(1, 10L, 10L))).isEmpty();
    assertThat(GENEROUS.firstBreach(new ContextUsage(500, 10L, 10L))).contains("items: 500 > 50");
  }

  /**
   * A label is metadata for the Context Inspector, and in this version it is not provider payload.
   *
   * <p>So it is deliberately outside {@link ContextBudget} in all three dimensions: characters,
   * bytes and the token estimate derived from them. A review flagged labels as "unbudgeted" and
   * this test is the answer rather than the fix — a ceiling that exists to bound what leaves the
   * platform must not be charged for text that does not leave it.
   *
   * <p>It is not outside everything else, and the body checks two of the three ways it is covered:
   * a label is redacted by {@code ContextRedaction}, and it is inside the canonical payload and so
   * inside {@link CompiledContextPack#packDigest()}. The third — the 500-character cap — is a
   * column width in V9 and a {@code @Column(length = 500)} on {@code ContextPackItemEntity}, and no
   * test asserts it in either place; it is named here so a reader knows where it lives and knows it
   * is unguarded, not so this test can take credit for it. "Not budgeted" is otherwise one careless
   * step from "not covered", which is why the other two are asserted rather than asserted about.
   *
   * <p><b>If labels are ever sent to a provider, {@link ContextBudget} must change with them</b> —
   * {@link ContextUsage#plus(ContextItem)} and {@link ContextItem#characterCount()} are the two
   * places that would move — and this test must be rewritten rather than deleted. A budget that
   * silently undercounts what is transmitted is the one failure this whole type exists to prevent.
   */
  @Test
  void labelIsInspectorMetadataNotProviderPayload() {
    String shortLabel = "s";
    String longLabel = "L".repeat(400);
    String content = "identical content in both packs";

    ContextItem withShortLabel =
        new ContextItem(
            "i-label",
            ContextKind.DECISION,
            shortLabel,
            content,
            new ContextProvenance(
                ContextSource.of(ContextSourceType.BRAIN_ENTRY, "dec-1"), PROJECT, OBSERVED));
    ContextItem withLongLabel =
        new ContextItem(
            "i-label",
            ContextKind.DECISION,
            longLabel,
            content,
            new ContextProvenance(
                ContextSource.of(ContextSourceType.BRAIN_ENTRY, "dec-1"), PROJECT, OBSERVED));

    // A label 399 characters longer costs the budget nothing, in any of the three dimensions.
    assertThat(withLongLabel.characterCount()).isEqualTo(withShortLabel.characterCount());
    assertThat(withLongLabel.byteCount()).isEqualTo(withShortLabel.byteCount());
    assertThat(withLongLabel.characterCount()).isEqualTo(content.length());

    ContextPack shortLabelled = pack(GENEROUS, List.of(withShortLabel));
    ContextPack longLabelled = pack(GENEROUS, List.of(withLongLabel));

    assertThat(longLabelled.usage()).isEqualTo(shortLabelled.usage());
    assertThat(longLabelled.usage().estimatedTokens().estimatedTokens())
        .isEqualTo(shortLabelled.usage().estimatedTokens().estimatedTokens());

    // A budget that would not fit the labels but fits the content admits both packs, which is the
    // decision stated as arithmetic rather than as prose.
    ContextBudget tighterThanTheLabel = new ContextBudget(5, content.length(), 4L * content.length());
    assertThat(tighterThanTheLabel.admits(longLabelled.usage())).isTrue();

    // And yet the label is not invisible: it is part of what a pack says it holds, so two packs
    // differing only in a label are different packs. Both digests are checked, because they are
    // different digests over different field lists and "the label is covered" has to be true of
    // the one the compiler actually publishes.
    assertThat(longLabelled.contentFingerprint()).isNotEqualTo(shortLabelled.contentFingerprint());
    assertThat(compiledDigestOf(withLongLabel)).isNotEqualTo(compiledDigestOf(withShortLabel));

    // And a label is redacted like any other stored free text - not budgeted is not the same as
    // not looked at.
    ContextItem secretLabelled =
        new ContextItem(
            "i-label",
            ContextKind.DECISION,
            "TOKEN=vc_label_fixture_5501234",
            content,
            new ContextProvenance(
                ContextSource.of(ContextSourceType.BRAIN_ENTRY, "dec-1"), PROJECT, OBSERVED));
    ContextItem afterRedaction = ContextRedaction.redact(secretLabelled).item();
    assertThat(afterRedaction.label()).doesNotContain("vc_label_fixture_5501234");
    assertThat(afterRedaction.label()).contains("[REDACTED]");
  }

  /** The compiler's digest over a one-item pack, which is where the published claim lives. */
  private static String compiledDigestOf(ContextItem item) {
    return new CompiledContextPack(
            UUID.fromString("00000000-0000-0000-0000-0000000000c3"),
            PROJECT,
            "TASK-CTX-01",
            Instant.parse("2026-02-02T12:00:00Z"),
            GENEROUS,
            ContextPolicyVersion.CURRENT,
            List.of(
                new AdmittedContextItem(
                    ContextRedaction.redact(item),
                    ContextAdmission.allow(
                        "context.policy.test", "Fixture admission for the label decision."))))
        .packDigest();
  }
}
