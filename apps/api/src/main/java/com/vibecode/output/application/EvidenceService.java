package com.vibecode.output.application;

import com.vibecode.brain.application.MemoryProposalService;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.brain.domain.MemoryProposalTrigger;
import com.vibecode.output.domain.EvidenceType;
import com.vibecode.project.application.ProjectService;
import com.vibecode.output.domain.OutputAnalysis;
import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.domain.TaskEvidence;
import com.vibecode.output.infrastructure.OutputAnalysisRecordRepository;
import com.vibecode.output.infrastructure.TaskEvidenceRepository;
import com.vibecode.task.application.TaskCompletionPolicy.CompletionAssessment;
import com.vibecode.task.application.TaskService;
import com.vibecode.output.domain.OutputAnalysisStatus;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what actually happened during a task and lets the verdict move the task.
 *
 * <pre>
 *   output -&gt; analyze -&gt; TaskEvidence -&gt; OutputAnalysisRecord -&gt; task status
 * </pre>
 *
 * <p>Evidence is append-only: submitting new evidence never edits or deletes the old, so the trail
 * still shows the failure that preceded a fix.
 */
@Service
@Transactional
public class EvidenceService {

  private final TaskService tasks;
  private final TaskEvidenceRepository evidence;
  private final OutputAnalysisRecordRepository analyses;
  private final OutputAnalyzer analyzer;
  private final MemoryProposalService memory;
  private final ProjectService projects;

  public EvidenceService(
      TaskService tasks,
      TaskEvidenceRepository evidence,
      OutputAnalysisRecordRepository analyses,
      OutputAnalyzer analyzer,
      MemoryProposalService memory,
      ProjectService projects) {
    this.tasks = tasks;
    this.projects = projects;
    this.evidence = evidence;
    this.analyses = analyses;
    this.analyzer = analyzer;
    this.memory = memory;
  }

  /** What recording one piece of evidence produced. */
  public record EvidenceRecorded(
      TaskEvidence evidence,
      OutputAnalysisRecord analysis,
      Task task,
      CompletionAssessment completion) {}

  public EvidenceRecorded record(
      UUID projectId, UUID taskId, EvidenceType type, String rawContent, String source) {
    projects.requireWritable(projectId);
    Task task = tasks.require(projectId, taskId);
    // Copy the status out of the entity rather than holding the entity: it is the same managed
    // instance the update below mutates, so a reference would report the new status as the old one.
    TaskStatus statusBefore = task.getStatus();
    // Likewise read the previous verdict first — whether this evidence resolves an earlier failure
    // is only knowable against the one that preceded it.
    Optional<OutputAnalysisRecord> previous = latestAnalysisForTask(taskId);

    TaskEvidence stored =
        evidence.save(new TaskEvidence(projectId, taskId, type, rawContent, source));
    OutputAnalysis analysis = analyzer.analyze(rawContent);
    OutputAnalysisRecord record = analyses.save(new OutputAnalysisRecord(stored.getId(), analysis));

    CompletionAssessment completion = applyToTask(projectId, taskId, record);
    Task updated = tasks.require(projectId, taskId);
    proposeMemory(projectId, statusBefore, updated, previous, record, source);
    return new EvidenceRecorded(stored, record, updated, completion);
  }

  /**
   * Turns what just happened into proposed memory.
   *
   * <p>Nothing here writes to the Project Brain. Each event becomes a proposal the user reviews,
   * so the official memory only ever grows by an explicit decision.
   */
  private void proposeMemory(
      UUID projectId,
      TaskStatus statusBefore,
      Task after,
      Optional<OutputAnalysisRecord> previous,
      OutputAnalysisRecord current,
      String source) {

    boolean wasFailing =
        previous
            .map(OutputAnalysisRecord::getStatus)
            .filter(
                status ->
                    status == OutputAnalysisStatus.FAILURE
                        || status == OutputAnalysisStatus.BLOCKED
                        || status == OutputAnalysisStatus.PARTIAL)
            .isPresent();

    switch (current.getStatus()) {
      case FAILURE, BLOCKED, PARTIAL ->
          memory.propose(
              projectId,
              MemoryProposalTrigger.ERROR_FOUND,
              BrainEntryType.ERROR,
              "Falha em: " + after.getTitle(),
              current.getSummary()
                  + "\nSinais: "
                  + String.join(", ", current.getSignalNames()),
              source);
      case SUCCESS -> {
        if (wasFailing) {
          memory.propose(
              projectId,
              MemoryProposalTrigger.ERROR_RESOLVED,
              BrainEntryType.SOLUTION,
              "Erro resolvido em: " + after.getTitle(),
              "A evidência anterior falhava e a mais recente comprova sucesso técnico.",
              source);
        }
        if (statusBefore != TaskStatus.COMPLETED && after.getStatus() == TaskStatus.COMPLETED) {
          memory.propose(
              projectId,
              MemoryProposalTrigger.TASK_COMPLETED,
              BrainEntryType.COMPLETED_STEP,
              "Tarefa concluída: " + after.getTitle(),
              after.getObjective(),
              source);
        }
      }
      default -> {
        // NEEDS_VALIDATION and UNKNOWN say nothing worth remembering yet.
      }
    }
  }

  /**
   * Translates a verdict into a task movement.
   *
   * <p>Only a SUCCESS verdict is even allowed to attempt completion, and even then the completion
   * policy still has to agree — success on one build does not satisfy criteria nobody checked.
   */
  private CompletionAssessment applyToTask(
      UUID projectId, UUID taskId, OutputAnalysisRecord record) {
    return switch (record.getStatus()) {
      case SUCCESS -> tasks.tryComplete(projectId, taskId, Optional.of(record));
      case BLOCKED -> {
        tasks.markBlocked(projectId, taskId);
        yield null;
      }
      case FAILURE, PARTIAL -> {
        tasks.markNeedsWork(projectId, taskId);
        yield null;
      }
      case NEEDS_VALIDATION, UNKNOWN -> tasks.tryComplete(projectId, taskId, Optional.of(record));
    };
  }

  @Transactional(readOnly = true)
  public List<TaskEvidence> listForTask(UUID projectId, UUID taskId) {
    projects.requireReadable(projectId);
    tasks.require(projectId, taskId);
    return evidence.findByTaskIdOrderByCreatedAtDescIdDesc(taskId);
  }

  @Transactional(readOnly = true)
  public List<TaskEvidence> listRecentForProject(UUID projectId, int limit) {
    // This query goes straight to a project id, so it has to authorize on its own; nothing
    // upstream does it for us.
    projects.requireReadable(projectId);
    return evidence.findByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
        .limit(limit)
        .toList();
  }

  @Transactional(readOnly = true)
  public Optional<TaskEvidence> latestForTask(UUID taskId) {
    return evidence.findFirstByTaskIdOrderByCreatedAtDescIdDesc(taskId);
  }

  @Transactional(readOnly = true)
  public Optional<OutputAnalysisRecord> latestAnalysisForTask(UUID taskId) {
    return latestForTask(taskId).flatMap(item -> analyses.findByEvidenceId(item.getId()));
  }

  @Transactional(readOnly = true)
  public Optional<OutputAnalysisRecord> analysisOf(UUID evidenceId) {
    return analyses.findByEvidenceId(evidenceId);
  }
}
