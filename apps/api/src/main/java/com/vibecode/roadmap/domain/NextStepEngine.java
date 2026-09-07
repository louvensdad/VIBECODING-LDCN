package com.vibecode.roadmap.domain;

import com.vibecode.brain.domain.ProjectBrain;
import com.vibecode.output.domain.OutputAnalysis;
import java.util.Optional;

/**
 * Decides what the project should do next.
 *
 * <p>Its inputs are the official state — memory, plan, current task and the last analyzed output —
 * and never a model's opinion. Contract only in this phase; no implementation is registered yet.
 *
 * <pre>
 *   ProjectBrain + Roadmap + current Task + OutputAnalysis =&gt; NextStepRecommendation
 * </pre>
 */
public interface NextStepEngine {

  NextStepRecommendation recommend(
      ProjectBrain brain,
      Roadmap roadmap,
      Optional<com.vibecode.task.domain.Task> currentTask,
      Optional<OutputAnalysis> lastAnalysis);
}
