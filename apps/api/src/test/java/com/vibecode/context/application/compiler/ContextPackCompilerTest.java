package com.vibecode.context.application.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.brain.application.BrainService;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.context.application.policy.ContextPolicy;
import com.vibecode.context.application.policy.ContextPolicyRule;
import com.vibecode.context.application.policy.DefaultContextPolicyRules;
import com.vibecode.context.application.source.CandidateContextCollection;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextPolicyVersion;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.guardian.domain.SecurityCategory;
import com.vibecode.guardian.domain.SecurityFinding;
import com.vibecode.guardian.domain.SecurityFindingStatus;
import com.vibecode.guardian.domain.SecuritySeverity;
import com.vibecode.guardian.domain.SecuritySourceType;
import com.vibecode.guardian.infrastructure.SecurityFindingRepository;
import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.domain.EvidenceType;
import com.vibecode.project.application.ProjectService;
import com.vibecode.project.domain.Project;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.support.TestIdentity;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The whole pipeline, over a project that actually has records in it.
 *
 * <p>The unit tests beside this one pin each step in isolation. What only an end-to-end run can
 * show is that the steps are wired in the order the design requires: that the policy runs against
 * real candidates rather than a fixture, that what comes out is narrower than what went in, and
 * that every surviving item can name the rule that admitted it.
 *
 * <p>The fixture is deliberately noisy - every brain entry type, a roadmap, evidence, a security
 * finding - because deny-by-default is only interesting when there is plenty available to deny.
 */
@SpringBootTest
class ContextPackCompilerTest {

  /** Generous, so that the budget tests are the only ones where a ceiling decides anything. */
  private static final ContextBudget GENEROUS = new ContextBudget(500, 1_000_000L, 2_000_000L);

  @Autowired ContextPackCompiler compiler;
  @Autowired CandidateContextCollection candidates;
  @Autowired ContextPolicy policy;
  @Autowired Clock clock;
  @Autowired TestIdentity identity;
  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TaskService tasks;
  @Autowired BrainService brain;
  @Autowired EvidenceService evidence;
  @Autowired SecurityFindingRepository findings;

  private UUID projectId;
  private UUID criticalFindingId;

  @BeforeEach
  void createProject() {
    identity.createAndAuthenticate("compiler-owner");
    Project project =
        projects.create(
            "Compiler fixture project",
            "A project that exists so the compiler has something to compile",
            "Guide a solo developer through building a small tool");
    projectId = project.getId();

    RoadmapPhase phase = roadmaps.addPhase(projectId, 1, "Foundations", "Set the shape");
    Task task =
        tasks.addTask(
            projectId,
            phase.getId(),
            1,
            "Model the context domain",
            "Describe context as a domain before anything reads it",
            RiskLevel.LOW);
    tasks.addCriterion(projectId, task.getId(), "Every item carries provenance", true);

    evidence.record(
        projectId,
        task.getId(),
        EvidenceType.BUILD_RESULT,
        "BUILD FAILURE\ncompilation error in ContextItem.java",
        "maven");
    tasks.markBlocked(projectId, task.getId());

    for (BrainEntryType type : BrainEntryType.values()) {
      brain.add(
          projectId,
          type,
          "Entry of type " + type,
          "What the project remembers as " + type,
          "test");
    }

    // A CRITICAL finding, left open. Compiling a pack must not touch it.
    SecurityFinding critical =
        findings.save(
            new SecurityFinding(
                UUID.randomUUID(),
                projectId,
                SecuritySourceType.TASK_EVIDENCE,
                task.getId().toString(),
                SecurityCategory.SECRET_EXPOSURE,
                SecuritySeverity.CRITICAL,
                "Synthetic critical finding",
                "Synthetic description that must never reach a pack",
                "REDACTED",
                "src/test/fixture",
                "Synthetic recommendation",
                "SEC-TEST",
                UUID.randomUUID().toString()));
    criticalFindingId = critical.getId();
  }

  @AfterEach
  void signOut() {
    identity.clear();
  }

  @Test
  @DisplayName("What is available is far more than what is admitted")
  void availableIsNotAdmitted() {
    List<ContextItem> available = candidates.collect(projectId);
    CompiledContextPack compiled = compiler.compile(projectId, "TASK-42", GENEROUS);

    // The budget is not what is doing the narrowing here: it is generous enough to hold everything.
    assertThat(available.size()).isGreaterThan(compiled.size());
    assertThat(compiled.size()).isLessThan(available.size());
    assertThat(compiled.isEmpty()).isFalse();
  }

  @Test
  @DisplayName("Nothing the policy does not name reaches the pack")
  void denyByDefaultHoldsEndToEnd() {
    CompiledContextPack compiled = compiler.compile(projectId, "TASK-42", GENEROUS);

    Set<ContextSourceType> admittedSources =
        compiled.admittedItems().stream()
            .map(admitted -> admitted.item().provenance().sourceType())
            .collect(java.util.stream.Collectors.toSet());
    Set<ContextKind> admittedKinds =
        compiled.admittedItems().stream()
            .map(admitted -> admitted.item().kind())
            .collect(java.util.stream.Collectors.toSet());

    // The roadmap is collected and no rule admits it, so it is absent - not because anything
    // filtered it, but because nothing let it in.
    assertThat(admittedSources).doesNotContain(ContextSourceType.ROADMAP);
    // A model's own earlier output, and the notes nobody could classify.
    assertThat(admittedKinds).doesNotContain(ContextKind.PROMPT_RESULT, ContextKind.NOTE);
    // Remembered claims about progress lose to the computed state, so they are not admitted either.
    assertThat(admittedKinds).doesNotContain(ContextKind.COMPLETED_STEP, ContextKind.SOLUTION);

    // And the candidates really did contain all of those, so the absences above mean something.
    List<ContextItem> available = candidates.collect(projectId);
    assertThat(available)
        .extracting(item -> item.provenance().sourceType())
        .contains(ContextSourceType.ROADMAP);
    assertThat(available)
        .extracting(ContextItem::kind)
        .contains(ContextKind.PROMPT_RESULT, ContextKind.NOTE, ContextKind.COMPLETED_STEP);
  }

  @Test
  @DisplayName("Every item in the pack names a declared rule and carries its explanation")
  void everyItemCanAccountForItself() {
    CompiledContextPack compiled = compiler.compile(projectId, "TASK-42", GENEROUS);
    List<String> declaredRuleIds = policy.rules().stream().map(ContextPolicyRule::ruleId).toList();

    assertThat(compiled.admittedItems()).isNotEmpty();
    for (AdmittedContextItem admitted : compiled.admittedItems()) {
      assertThat(admitted.admission().isAllowed()).isTrue();
      assertThat(admitted.admission().policyRuleId()).isIn(declaredRuleIds);
      assertThat(admitted.admission().explanation()).isNotBlank();
      // Never the default deny: that rule id can only ever accompany a refusal, and a refused item
      // has no business being in a pack.
      assertThat(admitted.admission().policyRuleId())
          .isNotEqualTo(ContextPolicy.DEFAULT_DENY_RULE_ID);
    }
  }

  @Test
  @DisplayName("Two compilations of unchanged state agree on the payload and the digest")
  void sameLogicalInputSamePayloadAndDigest() {
    CompiledContextPack first = compiler.compile(projectId, "TASK-42", GENEROUS);
    CompiledContextPack second = compiler.compile(projectId, "TASK-42", GENEROUS);

    // The two things that always differ, stated out loud so the claim below is not mistaken for a
    // claim that the rows are identical.
    assertThat(second.packId()).isNotEqualTo(first.packId());

    assertThat(second.canonicalPayload().value()).isEqualTo(first.canonicalPayload().value());
    assertThat(second.packDigest()).isEqualTo(first.packDigest());
    assertThat(second.admittedItems())
        .extracting(AdmittedContextItem::id)
        .containsExactlyElementsOf(first.admittedItems().stream().map(AdmittedContextItem::id).toList());
  }

  @Test
  @DisplayName("The item order is the canonical one, and it does not move between runs")
  void orderingIsStable() {
    CompiledContextPack first = compiler.compile(projectId, "TASK-42", GENEROUS);
    CompiledContextPack second = compiler.compile(projectId, "TASK-42", GENEROUS);

    List<ContextItem> items = first.pack().items();
    List<ContextItem> sorted = new java.util.ArrayList<>(items);
    sorted.sort(ContextItem.CANONICAL_ORDER);
    assertThat(items).containsExactlyElementsOf(sorted);

    assertThat(second.pack().items()).containsExactlyElementsOf(items);
  }

  @Test
  @DisplayName("The same items under a different policy version digest differently")
  void policyVersionChangesTheDigest() {
    CompiledContextPack underOne = compiler.compile(projectId, "TASK-42", GENEROUS);

    // The same rules, a different version stamp. Nothing about the project changed.
    ContextPackCompiler asVersionTwo =
        new ContextPackCompiler(
            candidates,
            new ContextPolicy(new ContextPolicyVersion("2"), DefaultContextPolicyRules.rules()),
            clock);
    CompiledContextPack underTwo = asVersionTwo.compile(projectId, "TASK-42", GENEROUS);

    assertThat(underTwo.pack().contentFingerprint())
        .isEqualTo(underOne.pack().contentFingerprint());
    assertThat(underTwo.packDigest()).isNotEqualTo(underOne.packDigest());
  }

  @Test
  @DisplayName("A binding budget shortens the pack without cutting any item")
  void aBindingBudgetDropsWholeItems() {
    CompiledContextPack full = compiler.compile(projectId, "TASK-42", GENEROUS);
    assertThat(full.size()).isGreaterThan(2);

    CompiledContextPack capped =
        compiler.compile(projectId, "TASK-42", new ContextBudget(2, 1_000_000L, 2_000_000L));

    assertThat(capped.size()).isEqualTo(2);
    // Each surviving item is the whole item, identical to the one the generous run produced.
    for (AdmittedContextItem admitted : capped.admittedItems()) {
      ContextItem fromFullRun =
          full.admittedItems().stream()
              .filter(candidate -> candidate.id().equals(admitted.id()))
              .findFirst()
              .orElseThrow()
              .item();
      assertThat(admitted.item().content()).isEqualTo(fromFullRun.content());
    }
    assertThat(capped.usage().items()).isEqualTo(2);
    assertThat(full.budget().admits(full.usage())).isTrue();
    assertThat(capped.budget().admits(capped.usage())).isTrue();
  }

  @Test
  @DisplayName("Compiling a pack resolves nothing: a CRITICAL finding is exactly where it was")
  void theSecurityGateIsNotBypassed() {
    SecurityFinding before = findings.findById(criticalFindingId).orElseThrow();
    SecurityFindingStatus statusBefore = before.getStatus();
    long countBefore = findings.findByProjectIdOrderByCreatedAtDesc(projectId).size();

    CompiledContextPack compiled = compiler.compile(projectId, "TASK-42", GENEROUS);

    // The posture may well be in the pack - it is admitted by a named rule, so the work can see
    // what is outstanding. Seeing it is not settling it.
    SecurityFinding after = findings.findById(criticalFindingId).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(statusBefore);
    assertThat(after.getSeverity()).isEqualTo(SecuritySeverity.CRITICAL);
    assertThat(after.getResolvedAt()).isNull();
    assertThat(after.getResolutionReason()).isNull();
    assertThat(findings.findByProjectIdOrderByCreatedAtDesc(projectId)).hasSize((int) countBefore);

    // And the finding's own text stayed in the guardian's tables: a pack carries a summary.
    for (AdmittedContextItem admitted : compiled.admittedItems()) {
      assertThat(admitted.item().content())
          .doesNotContain("Synthetic description that must never reach a pack");
    }
  }
}
