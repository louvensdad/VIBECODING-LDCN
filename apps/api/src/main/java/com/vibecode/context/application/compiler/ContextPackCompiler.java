package com.vibecode.context.application.compiler;

import com.vibecode.context.application.policy.ContextPolicy;
import com.vibecode.context.application.redaction.ContextRedaction;
import com.vibecode.context.application.source.CandidateContextCollection;
import com.vibecode.context.application.source.ContextReadWindow;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.domain.ContextItem;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns everything the records hold into the one pack a task actually gets.
 *
 * <p>The pipeline, in the order it runs and in the order it must stay:
 *
 * <ol>
 *   <li><b>Candidates</b> — every source reports what it holds, unfiltered. Collection is not
 *       inclusion.
 *   <li><b>Policy</b> — {@link ContextPolicy} decides, deny-by-default. An item enters only because
 *       a named rule said it may.
 *   <li><b>Redaction</b> — {@link ContextRedaction} runs on what survived, before anything measures
 *       or stores it.
 *   <li><b>Measurement and selection</b> — {@link BudgetedContextSelection} fits the redacted items
 *       under the budget, at item boundaries.
 *   <li><b>Materialisation</b> — a {@link CompiledContextPack}, which cannot be built from anything
 *       but admitted items.
 * </ol>
 *
 * <p><b>Why redaction comes before measurement and not after.</b> The redacted text is what would
 * leave the platform, so it is what the ceiling must be enforced against. Measuring first would
 * mean sizing a pack by text nobody will ever see; redacting after selection would mean the digest
 * and the stored rows disagreed about what the pack contained.
 *
 * <p><b>The task reference goes through redaction too, and here rather than at the database.</b> It
 * is not an item and it is not measured, but it is user text: callers compose it from a task title
 * somebody wrote, and it lands in a column of its own. Redacting it in this method means the
 * canonical payload and the stored row carry the same string; redacting it at the persistence
 * boundary would leave a digest taken over text that exists nowhere.
 *
 * <p><b>Why denied items leave nothing behind.</b> A denied candidate is dropped here and its
 * content goes no further — not into the pack, not into a shadow table, not into a log line. The
 * obvious convenience, keeping the raw text so the Inspector can show what was excluded, would
 * recreate the exact leak this pipeline exists to prevent: the content that policy refused would be
 * sitting in the database in the clear. What was excluded is answerable by re-running the policy
 * against today's records, which is a different and honest question.
 *
 * <p><b>What this class does not do.</b> It does not resolve a secret; the context module cannot
 * see the vault at all, and an architecture test enforces that. It does not close a security
 * finding: compiling a pack that mentions the posture changes no finding's status, and a CRITICAL
 * one remains the Security Gate's business exactly as before. And it does not persist — see {@link
 * ContextPackAssembler}, which is where a compiled pack meets the database.
 */
@Service
@Transactional(readOnly = true)
public class ContextPackCompiler {

  // The read-only flag is defence in depth and not the guarantee. Nothing in this class or in
  // anything it calls writes; that is what makes a compilation side-effect free, and it is
  // structural rather than annotated. The annotation only takes effect when compile() starts the
  // transaction, which is what happens when it is called directly. Called through
  // ContextPackAssembler - whose job is to write - the assembler's read-write transaction is
  // already open and the default propagation joins it, so the flag is dropped exactly where it
  // would have mattered. Stated rather than fixed: making it REQUIRES_NEW would put the read of
  // the candidates in a different transaction from the write of the pack, which is a worse
  // property than the one it would buy.

  private final CandidateContextCollection candidates;
  private final ContextPolicy policy;
  private final Clock clock;

  public ContextPackCompiler(
      CandidateContextCollection candidates, ContextPolicy policy, Clock clock) {
    this.candidates = candidates;
    this.policy = policy;
    this.clock = clock;
  }

  /** Compiles with the default read window. */
  public CompiledContextPack compile(UUID projectId, String taskReference, ContextBudget budget) {
    return compile(projectId, taskReference, budget, ContextReadWindow.DEFAULT);
  }

  /**
   * Compiles the pack for one task.
   *
   * <p>The pack id and the assembly instant are the two things that legitimately differ between two
   * compilations of unchanged state; everything else about the result is a function of the inputs.
   * That is why {@link CompiledContextPack#packDigest()} and not the row is what determinism is
   * claimed of.
   *
   * @param projectId the project to read; authorization happens in the collectors
   * @param taskReference the task the context is for; part of what "the same inputs" means
   * @param budget the ceiling, in items, characters and UTF-8 bytes
   * @param window how many rows an unbounded source may be asked for; a query bound, not a policy
   */
  public CompiledContextPack compile(
      UUID projectId, String taskReference, ContextBudget budget, ContextReadWindow window) {
    if (projectId == null) {
      throw new IllegalArgumentException("A pack must be compiled for a project");
    }
    if (budget == null) {
      throw new IllegalArgumentException("A pack must be compiled against a budget");
    }

    List<AdmittedContextItem> admitted = new ArrayList<>();
    for (ContextItem candidate : candidates.collect(projectId, window)) {
      ContextAdmission admission = policy.admit(candidate);
      if (!admission.isAllowed()) {
        // Dropped here and nowhere recorded. See the class javadoc: keeping the text of a refused
        // item is the leak this pipeline exists to prevent.
        continue;
      }
      admitted.add(new AdmittedContextItem(ContextRedaction.redact(candidate), admission));
    }

    return new CompiledContextPack(
        UUID.randomUUID(),
        projectId,
        ContextRedaction.redactTaskReference(taskReference),
        clock.instant(),
        budget,
        policy.version(),
        BudgetedContextSelection.select(admitted, budget));
  }
}
