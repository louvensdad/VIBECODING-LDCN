package com.vibecode.guide.web;

import com.vibecode.guide.application.DeterministicNextStepEngine;
import com.vibecode.guide.application.DeterministicProjectGuide;
import com.vibecode.guide.domain.GuidanceReport;
import com.vibecode.guide.domain.NextStepPriority;
import com.vibecode.guide.domain.NextStepRecommendation;
import com.vibecode.guide.domain.NextStepType;
import com.vibecode.prompt.domain.PromptType;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Orientation endpoints. Neither of these changes anything. */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class GuideController {

  private final DeterministicProjectGuide guide;
  private final DeterministicNextStepEngine nextStep;

  public GuideController(
      DeterministicProjectGuide guide, DeterministicNextStepEngine nextStep) {
    this.guide = guide;
    this.nextStep = nextStep;
  }

  @GetMapping("/guide")
  public GuidanceResponse guide(@PathVariable UUID projectId) {
    return GuidanceResponse.from(guide.describe(projectId));
  }

  @GetMapping("/next-step")
  public NextStepResponse nextStep(@PathVariable UUID projectId) {
    return NextStepResponse.from(nextStep.recommend(projectId));
  }

  public record NextStepResponse(
      NextStepType type,
      String title,
      String reason,
      UUID taskId,
      NextStepPriority priority,
      List<String> blockingIssues,
      List<String> requiredActions,
      PromptType suggestedPromptType) {

    static NextStepResponse from(NextStepRecommendation recommendation) {
      return new NextStepResponse(
          recommendation.type(),
          recommendation.title(),
          recommendation.reason(),
          recommendation.taskId(),
          recommendation.priority(),
          recommendation.blockingIssues(),
          recommendation.requiredActions(),
          recommendation.suggestedPromptType());
    }
  }

  public record GuidanceResponse(
      UUID projectId,
      String whereYouAre,
      List<String> whatWasCompleted,
      List<String> whatIsMissing,
      List<String> activeProblems,
      NextStepResponse recommendedNextStep,
      String reason,
      int progressPercentage) {

    static GuidanceResponse from(GuidanceReport report) {
      return new GuidanceResponse(
          report.projectId(),
          report.whereYouAre(),
          report.whatWasCompleted(),
          report.whatIsMissing(),
          report.activeProblems(),
          NextStepResponse.from(report.recommendedNextStep()),
          report.reason(),
          report.progressPercentage());
    }
  }
}
