package com.vibecode.guide.domain;

import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.state.domain.ProjectState;
import com.vibecode.task.application.TaskCompletionPolicy.CompletionAssessment;
import com.vibecode.task.domain.Task;
import java.util.List;
import java.util.Optional;

/** Everything a rule is allowed to look at. All of it is recorded state. */
public record NextStepContext(
    ProjectState state,
    Optional<OutputAnalysisRecord> latestAnalysis,
    Optional<CompletionAssessment> currentTaskCompletion,
    List<BrainEntry> relevantMemory) {

  public NextStepContext {
    relevantMemory = List.copyOf(relevantMemory);
  }

  public Optional<Task> currentTask() {
    return state.currentTaskOptional();
  }
}
