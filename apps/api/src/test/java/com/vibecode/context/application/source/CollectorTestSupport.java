package com.vibecode.context.application.source;

import com.vibecode.brain.application.BrainService;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.guardian.domain.SecurityCategory;
import com.vibecode.guardian.domain.SecurityFinding;
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
import com.vibecode.task.domain.TaskAcceptanceCriterion;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A project carrying at least one record of every source the collectors read.
 *
 * <p>Every fixture is synthetic and deliberately dull. Nothing here is a credential, resembles one,
 * or would matter if it leaked — the security fixture is a finding row whose evidence field is
 * literally the word REDACTED, because a test that needed a real-looking secret to be meaningful
 * would be testing the wrong thing.
 */
@SpringBootTest
abstract class CollectorTestSupport {

  @Autowired TestIdentity identity;
  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TaskService tasks;
  @Autowired BrainService brain;
  @Autowired EvidenceService evidence;
  @Autowired SecurityFindingRepository findings;
  @Autowired CandidateContextCollection candidates;

  /**
   * Everything one populated project holds, so a test can assert against the exact record it wrote
   * rather than against whatever came back.
   */
  record Fixture(
      Project project,
      RoadmapPhase firstPhase,
      RoadmapPhase secondPhase,
      RoadmapPhase phaseWithoutTasks,
      Task blockedTask,
      Task readyTask,
      TaskAcceptanceCriterion requiredCriterion,
      TaskAcceptanceCriterion optionalCriterion,
      UUID failingEvidenceId,
      UUID failingAnalysisId,
      SecurityFinding openFinding) {

    UUID projectId() {
      return project.getId();
    }
  }

  @AfterEach
  void clearAuthentication() {
    identity.clear();
  }

  /** Signs in a fresh user and gives them a project with a record of every source type. */
  Fixture createFullProject(String label) {
    identity.createAndAuthenticate(label);
    Project project =
        projects.create(
            "Context fixture project",
            "A project that exists so collectors have something to read",
            "Guide a solo developer through building a small tool");

    RoadmapPhase first =
        roadmaps.addPhase(project.getId(), 1, "Foundations", "Set the shape of the system");
    RoadmapPhase second =
        roadmaps.addPhase(project.getId(), 2, "Delivery", "Put it in front of a user");
    RoadmapPhase empty =
        roadmaps.addPhase(project.getId(), 3, "Hardening", "Planned, not yet broken down");

    Task blocked =
        tasks.addTask(
            project.getId(),
            first.getId(),
            1,
            "Model the context domain",
            "Describe context as a domain before anything reads it",
            RiskLevel.LOW);
    Task ready =
        tasks.addTask(
            project.getId(),
            second.getId(),
            1,
            "Publish the inspector",
            "Show the user exactly what was sent",
            RiskLevel.MEDIUM);

    TaskAcceptanceCriterion required =
        tasks.addCriterion(
            project.getId(), blocked.getId(), "Every item carries provenance", true);
    TaskAcceptanceCriterion optional =
        tasks.addCriterion(project.getId(), blocked.getId(), "The javadoc reads well", false);

    // A failing run: the analyzer reads this as a failure, which sets requiresCorrection on the
    // stored verdict. That flag is what makes the analysis an active error.
    EvidenceService.EvidenceRecorded recorded =
        evidence.record(
            project.getId(),
            blocked.getId(),
            EvidenceType.BUILD_RESULT,
            "BUILD FAILURE\ncompilation error in ContextItem.java",
            "maven");

    // Recording failing evidence moves the task back to in progress; blocking it is a separate,
    // deliberate act, so it has to come afterwards.
    tasks.markBlocked(project.getId(), blocked.getId());

    for (BrainEntryType type : BrainEntryType.values()) {
      brain.add(
          project.getId(),
          type,
          "Entry of type " + type,
          "What the project remembers as " + type,
          "test");
    }

    SecurityFinding finding =
        findings.save(
            new SecurityFinding(
                UUID.randomUUID(),
                project.getId(),
                SecuritySourceType.TASK_EVIDENCE,
                blocked.getId().toString(),
                SecurityCategory.SECRET_EXPOSURE,
                SecuritySeverity.HIGH,
                "Synthetic finding title that must never reach a pack",
                "Synthetic description that must never reach a pack",
                "REDACTED",
                "src/test/fixture",
                "Synthetic recommendation",
                "SEC-TEST",
                UUID.randomUUID().toString()));

    return new Fixture(
        projects.requireReadable(project.getId()),
        // Re-read rather than returned as constructed: adding tasks makes the status calculator
        // touch a phase row, so the instance addPhase handed back is already out of date.
        roadmaps.requirePhase(project.getId(), first.getId()),
        roadmaps.requirePhase(project.getId(), second.getId()),
        roadmaps.requirePhase(project.getId(), empty.getId()),
        tasks.require(project.getId(), blocked.getId()),
        tasks.require(project.getId(), ready.getId()),
        required,
        optional,
        recorded.evidence().getId(),
        recorded.analysis().getId(),
        finding);
  }
}
