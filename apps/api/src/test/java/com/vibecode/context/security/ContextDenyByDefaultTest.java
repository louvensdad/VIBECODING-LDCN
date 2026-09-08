package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.application.policy.ContextPolicy;
import com.vibecode.context.application.source.ContextReadWindow;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Collection is not inclusion.
 *
 * <p>Three things get conflated whenever someone reasons about this engine informally, and every
 * assertion here keeps them apart. An item is <b>collected</b> when a collector reads it out of the
 * project's records — that step has no opinion about content and does not redact. It is
 * <b>admitted</b> only when a named rule says it may be; nothing is admitted because nothing
 * objected. It is <b>redacted</b> after admission and before storage. An item can be collected and
 * refused, and the refusal is the normal case for most of the source/kind space.
 *
 * <p>The matrix test walks all 198 combinations of source type and kind rather than sampling. A
 * rule quietly widened to admit one more pair moves exactly one cell, which a sampled test would
 * miss and this one cannot.
 */
class ContextDenyByDefaultTest extends ContextProbeFixture {

  private Planted planted;

  @BeforeEach
  void plant() {
    planted = plantTheProbeEverywhere("deny-owner");
  }

  @Test
  @DisplayName("Every source and kind the policy does not name is denied, all 198 of them")
  void theWholeSourceAndKindSpaceIsDeniedExceptWhereARuleSaysOtherwise() {
    // Written out independently of DefaultContextPolicyRules, on purpose. Re-deriving the
    // expectation from the rules under test would make this assert that the code equals itself.
    Map<String, String> unexpected = new TreeMap<>();
    int allowed = 0;
    int denied = 0;

    for (ContextSourceType sourceType : ContextSourceType.values()) {
      for (ContextKind kind : ContextKind.values()) {
        ContextAdmission admission = policy.admit(probeItem(sourceType, kind));
        boolean shouldAllow = expectedToBeAdmitted(sourceType, kind);
        if (admission.isAllowed() != shouldAllow) {
          unexpected.put(
              sourceType + "/" + kind,
              (shouldAllow ? "expected ALLOW, got DENY by " : "expected DENY, got ALLOW by ")
                  + admission.policyRuleId());
        }
        if (admission.isAllowed()) {
          allowed++;
        } else {
          denied++;
        }
      }
    }

    assertThat(unexpected).isEmpty();
    assertThat(allowed + denied)
        .as("the whole space was walked")
        .isEqualTo(ContextSourceType.values().length * ContextKind.values().length);
    // The exact shape of the admitted surface, pinned rather than described. Note what it is NOT:
    // deny does not win most cells. Eight of the eleven source rules are declared with
    // `allowingSources`, which names a source and no kinds at all, so each of them admits every
    // kind except the two the deny rules take out — 8 x 16 = 128 of the 137 allowed cells. That is
    // deny-by-default in the sense that matters (a source no rule names gets nothing, and there is
    // no automatic inclusion), and it is emphatically not per-kind gating within a named source.
    // A collector that started emitting a different kind would not be stopped by policy, because
    // policy is not looking. Pinning the numbers means a rule widened by one cell fails here.
    assertThat(allowed).as("cells a rule admits").isEqualTo(137);
    assertThat(denied).as("cells with no rule to admit them").isEqualTo(61);
  }

  @Test
  @DisplayName("An invented source and an invented kind are both refused under the reserved rule")
  void aCombinationNoRuleNamesIsRefusedByName() {
    // ROADMAP is collected by a real collector and named by no rule. It is the live example of a
    // source that exists, is read on every compilation, and never enters a pack.
    ContextAdmission roadmapObjective =
        policy.admit(probeItem(ContextSourceType.ROADMAP, ContextKind.OBJECTIVE));
    assertThat(roadmapObjective.isAllowed()).isFalse();
    assertThat(roadmapObjective.policyRuleId()).isEqualTo(ContextPolicy.DEFAULT_DENY_RULE_ID);

    // And a kind under a source that IS named, but not for that kind: the project collector emits
    // a CURRENT_STATE item, and the project rule names only PROJECT_IDENTITY and VISION.
    ContextAdmission projectStatus =
        policy.admit(probeItem(ContextSourceType.PROJECT, ContextKind.CURRENT_STATE));
    assertThat(projectStatus.isAllowed()).isFalse();
    assertThat(projectStatus.policyRuleId()).isEqualTo(ContextPolicy.DEFAULT_DENY_RULE_ID);
    assertThat(projectStatus.explanation())
        .as("a refusal has to say why, or a reader cannot tell it from an oversight")
        .contains("deny-by-default");
  }

  @Test
  @DisplayName("Collected, admitted and redacted are three different sets, and the gaps are real")
  void collectionIsNotAdmissionAndAdmissionIsNotTheRawText() {
    List<ContextItem> collected =
        candidates.collect(planted.projectId(), ContextReadWindow.DEFAULT);

    // Collected: the raw probe is right there. Collection reads records, it does not scrub them.
    List<ContextItem> collectedWithProbe =
        collected.stream().filter(item -> item.content().contains(PROBE)).toList();
    assertThat(collectedWithProbe)
        .as("collection hands over what the records say, probe and all")
        .isNotEmpty();

    // Collected-but-refused: at least one whole source is read and never admitted.
    List<ContextItem> collectedRoadmap =
        collected.stream()
            .filter(item -> item.provenance().sourceType() == ContextSourceType.ROADMAP)
            .toList();
    assertThat(collectedRoadmap)
        .as("the roadmap collector must have produced something, or the next claim is empty")
        .isNotEmpty();

    CompiledContextPack compiled =
        assembler.assemble(planted.projectId(), "CTX-09 three sets", GENEROUS);
    Set<ContextSourceType> admittedSources =
        compiled.admittedItems().stream()
            .map(admitted -> admitted.item().provenance().sourceType())
            .collect(java.util.stream.Collectors.toSet());
    assertThat(admittedSources)
        .as("collected on every compilation, admitted on none")
        .doesNotContain(ContextSourceType.ROADMAP);

    // Redacted: nothing admitted still carries the probe, and something admitted carries a marker.
    assertThat(compiled.admittedItems())
        .allSatisfy(admitted -> assertThat(admitted.item().content()).doesNotContain(PROBE));
    assertThat(compiled.admittedItems())
        .anySatisfy(admitted -> assertThat(admitted.item().content()).contains("[REDACTED]"));

    // And the refused kinds are absent by item, not merely scrubbed.
    assertThat(compiled.admittedItems())
        .as("a NOTE and a PROMPT_RESULT are refused, so their non-secret marker cannot appear")
        .allSatisfy(
            admitted -> {
              assertThat(admitted.item().content()).doesNotContain(DENIED_MARKER);
              assertThat(admitted.item().label()).doesNotContain(DENIED_MARKER);
              assertThat(admitted.item().kind())
                  .isNotIn(ContextKind.NOTE, ContextKind.PROMPT_RESULT);
            });
  }

  @Test
  @DisplayName("Every admitted item names the rule that let it in, and no item names the default")
  void nothingIsInAPackWithoutARuleThatSaidSo() {
    CompiledContextPack compiled =
        assembler.assemble(planted.projectId(), "CTX-09 reasons", GENEROUS);
    assertThat(compiled.admittedItems()).isNotEmpty();

    List<String> reasons = new ArrayList<>();
    for (AdmittedContextItem admitted : compiled.admittedItems()) {
      assertThat(admitted.admission().isAllowed()).isTrue();
      assertThat(admitted.admission().policyRuleId())
          .as("item %s", admitted.id())
          .isNotBlank()
          .isNotEqualTo(ContextPolicy.DEFAULT_DENY_RULE_ID);
      assertThat(admitted.admission().explanation()).isNotBlank();
      reasons.add(admitted.admission().policyRuleId());
    }
    assertThat(reasons)
        .as("a pack drawn from a fully populated project exercises more than one rule")
        .hasSizeGreaterThan(1);

    Integer rowsWithoutAReason =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ?"
                + " AND (policy_rule_id IS NULL OR explanation IS NULL)",
            Integer.class,
            compiled.packId());
    assertThat(rowsWithoutAReason).isZero();
  }

  /** The expectation, restated from the policy's prose rather than read out of its code. */
  private static boolean expectedToBeAdmitted(ContextSourceType sourceType, ContextKind kind) {
    // Two denials outrank every allow, whatever the source.
    if (kind == ContextKind.PROMPT_RESULT || kind == ContextKind.NOTE) {
      return false;
    }
    return switch (sourceType) {
      case PROJECT -> kind == ContextKind.PROJECT_IDENTITY || kind == ContextKind.VISION;
      case CURRENT_TASK,
              ACCEPTANCE_CRITERIA,
              CURRENT_PHASE,
              CURRENT_STATE,
              ACTIVE_ERRORS,
              LATEST_EVIDENCE,
              LATEST_OUTPUT_ANALYSIS,
              SECURITY_SUMMARY ->
          true;
      case BRAIN_ENTRY ->
          Set.of(
                  ContextKind.VISION,
                  ContextKind.REQUIREMENT,
                  ContextKind.ARCHITECTURE,
                  ContextKind.TECHNOLOGY,
                  ContextKind.DECISION,
                  ContextKind.RULE,
                  ContextKind.CONSTRAINT)
              .contains(kind);
      case ROADMAP -> false;
    };
  }

  private static ContextItem probeItem(ContextSourceType sourceType, ContextKind kind) {
    return new ContextItem(
        "probe:" + sourceType + ":" + kind,
        kind,
        "Probe label",
        "Probe content",
        new ContextProvenance(
            ContextSource.of(sourceType, "probe-source"),
            UUID.nameUUIDFromBytes("context-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            Instant.parse("2026-01-01T00:00:00Z")));
  }
}
