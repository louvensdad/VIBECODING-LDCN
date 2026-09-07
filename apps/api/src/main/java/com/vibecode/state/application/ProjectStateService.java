package com.vibecode.state.application;

import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.domain.TaskEvidence;
import com.vibecode.project.application.ProjectService;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.state.domain.ProjectState;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "where is this project?" by reading roadmap, tasks and evidence together.
 *
 * <p>Pure read model: it writes nothing, and computes every figure it reports.
 */
@Service
@Transactional(readOnly = true)
public class ProjectStateService {

  private final ProjectService projects;
  private final RoadmapService roadmaps;
  private final TaskService tasks;
  private final EvidenceService evidence;

  public ProjectStateService(
      ProjectService projects,
      RoadmapService roadmaps,
      TaskService tasks,
      EvidenceService evidence) {
    this.projects = projects;
    this.roadmaps = roadmaps;
    this.tasks = tasks;
    this.evidence = evidence;
  }

  public ProjectState of(UUID projectId) {
    projects.requireExisting(projectId);
    List<Task> ordered = tasks.listOrdered(projectId);
    List<RoadmapPhase> phases = roadmaps.listPhases(projectId);

    Task currentTask = pickCurrentTask(ordered);
    RoadmapPhase currentPhase = pickCurrentPhase(phases, currentTask);

    int completed = (int) ordered.stream().filter(task -> task.getStatus().isFinished()).count();
    int blocked =
        (int) ordered.stream().filter(task -> task.getStatus() == TaskStatus.BLOCKED).count();

    TaskEvidence lastEvidence =
        currentTask == null ? null : evidence.latestForTask(currentTask.getId()).orElse(null);
    OutputAnalysisRecord lastAnalysis =
        lastEvidence == null ? null : evidence.analysisOf(lastEvidence.getId()).orElse(null);

    return new ProjectState(
        projectId,
        currentPhase,
        currentTask,
        ordered,
        completed,
        ordered.size(),
        blocked,
        activeProblems(ordered, lastAnalysis),
        lastEvidence,
        lastAnalysis,
        tasks.readyTasks(projectId),
        phasesWithoutTasks(phases, ordered));
  }

  private List<String> phasesWithoutTasks(List<RoadmapPhase> phases, List<Task> ordered) {
    return phases.stream()
        .filter(phase -> ordered.stream().noneMatch(task -> task.getPhaseId().equals(phase.getId())))
        .map(RoadmapPhase::getTitle)
        .toList();
  }

  /**
   * The task the user is actually on.
   *
   * <p>Attention goes to whatever is already open before anything new is started — a blocked or
   * failing task must not be quietly skipped in favour of the next fresh one. Within that, plan
   * order decides.
   */
  private Task pickCurrentTask(List<Task> ordered) {
    Comparator<Task> byAttention =
        Comparator.comparingInt((Task task) -> attentionRank(task.getStatus()));
    return ordered.stream()
        .filter(task -> !task.getStatus().isFinished())
        .min(byAttention.thenComparing(ordered::indexOf))
        .orElse(null);
  }

  private int attentionRank(TaskStatus status) {
    return switch (status) {
      case BLOCKED -> 0;
      case IN_PROGRESS -> 1;
      case NEEDS_VALIDATION -> 2;
      case READY -> 3;
      case PLANNED -> 4;
      case COMPLETED, SKIPPED -> 5;
    };
  }

  private RoadmapPhase pickCurrentPhase(List<RoadmapPhase> phases, Task currentTask) {
    if (currentTask != null) {
      return phases.stream()
          .filter(phase -> phase.getId().equals(currentTask.getPhaseId()))
          .findFirst()
          .orElse(null);
    }
    return phases.stream()
        .filter(phase -> phase.getStatus() != com.vibecode.roadmap.domain.PhaseStatus.COMPLETED)
        .findFirst()
        .orElse(phases.isEmpty() ? null : phases.get(phases.size() - 1));
  }

  /** Problems the user can act on right now, stated plainly. */
  private List<String> activeProblems(List<Task> ordered, OutputAnalysisRecord lastAnalysis) {
    List<String> problems = new ArrayList<>();
    ordered.stream()
        .filter(task -> task.getStatus() == TaskStatus.BLOCKED)
        .forEach(task -> problems.add("Tarefa bloqueada: " + task.getTitle()));

    Optional.ofNullable(lastAnalysis)
        .filter(OutputAnalysisRecord::isRequiresCorrection)
        .ifPresent(analysis -> problems.add(analysis.getSummary()));

    return List.copyOf(problems);
  }
}
