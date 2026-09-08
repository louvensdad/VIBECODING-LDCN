package com.vibecode.context.security;

import com.vibecode.brain.application.BrainService;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.context.application.compiler.ContextPackAssembler;
import com.vibecode.context.application.policy.ContextPolicy;
import com.vibecode.context.application.source.CandidateContextCollection;
import com.vibecode.context.domain.ContextBudget;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A project whose own records were written carelessly, so that every source the engine reads is
 * carrying the same synthetic probe.
 *
 * <p><b>The probe is planted in a shape the redactor recognises.</b> {@code
 * com.vibecode.guardian.domain.SensitiveDataRedactor} does not remove high-entropy strings on
 * sight — it removes a value that a key names as sensitive. So the probe is always written as
 * {@code TOKEN=<probe>} or {@code API_KEY=<probe>} — a <b>bare</b> key, never a prefixed one. The
 * redactor's key rule is anchored on {@code \b} and {@code _} is a word character, so {@code
 * DB_TOKEN=} does not match where {@code TOKEN=} does; that is a Guardian defect, it is measured on
 * its own in {@link ContextShapelessSecretBlastRadiusTest}, and the bare key is chosen here
 * deliberately so these tests are about the engine's redaction boundary rather than about that bug.
 * Planting the bare string and then asserting it
 * is gone would be asserting a guarantee this system does not make: a brain entry is the user's own
 * prose and the engine stores it as written. That distinction is the whole reason this fixture puts
 * the key in.
 *
 * <p><b>The probe is one long token, deliberately.</b> A short discriminator matched against whole
 * tables collides with random UUIDs — a four-character fragment hits roughly one suite run in fifty
 * — so nothing here ever matches on a fragment of it. The non-hex suffix {@code zqxw} means the
 * string cannot be produced by a UUID at any length.
 *
 * <p>Every value here is synthetic. Nothing resembles a credential that exists, and the database it
 * is written to is created and dropped by the test run.
 */
@SpringBootTest
abstract class ContextProbeFixture {

  /** The probe. High entropy, non-hex suffix, never matched on a fragment. */
  static final String PROBE = "vc_context_secret_probe_zqxw_928472";

  /** The probe wrapped in the key that makes the redactor act on it. */
  static final String KEYED_PROBE = "TOKEN=" + PROBE;

  /**
   * A second marker, carried only by records the policy refuses. It is not secret-shaped, so
   * redaction never touches it: if it reaches a pack, the item reached the pack, and no argument
   * about redaction can explain that away.
   */
  static final String DENIED_MARKER = "vc_context_denied_marker_zqxw_513907";

  static final ContextBudget GENEROUS = new ContextBudget(500, 1_000_000L, 2_000_000L);

  @Autowired TestIdentity identity;
  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TaskService tasks;
  @Autowired BrainService brain;
  @Autowired EvidenceService evidence;
  @Autowired ContextPackAssembler assembler;
  @Autowired CandidateContextCollection candidates;
  @Autowired ContextPolicy policy;
  @Autowired JdbcTemplate jdbc;

  /** Everything the fixture wrote, so a test can assert against the record rather than the result. */
  record Planted(Project project, RoadmapPhase phase, Task task, UUID evidenceId) {
    UUID projectId() {
      return project.getId();
    }
  }

  @AfterEach
  void clearAuthentication() {
    identity.clear();
  }

  /**
   * Signs a fresh user in and gives them a project carrying the probe in every source the brief
   * names: brain VISION, TECHNOLOGY, ERROR, SOLUTION and NOTE; the task objective; an acceptance
   * criterion; evidence; the analysis derived from it; and the project's own identity text, which
   * is what the computed current state is built from.
   */
  Planted plantTheProbeEverywhere(String label) {
    identity.createAndAuthenticate(label);
    Project project =
        projects.create(
            "Probe project deployed with " + KEYED_PROBE,
            "A project whose own records carry " + KEYED_PROBE + " because someone pasted it",
            "Guide a solo developer through building a small tool with " + KEYED_PROBE);
    UUID projectId = project.getId();

    RoadmapPhase phase =
        roadmaps.addPhase(projectId, 1, "Foundations", "Set the shape, using " + KEYED_PROBE);

    Task task =
        tasks.addTask(
            projectId,
            phase.getId(),
            1,
            "Wire the deployment",
            "Run the migration with " + KEYED_PROBE + " exported first.",
            RiskLevel.LOW);
    tasks.addCriterion(projectId, task.getId(), "The pipeline works with API_KEY=" + PROBE, true);

    EvidenceService.EvidenceRecorded recorded =
        evidence.record(
            projectId,
            task.getId(),
            EvidenceType.BUILD_RESULT,
            "BUILD FAILURE\nconnecting with " + KEYED_PROBE + " failed",
            "maven");

    brain.add(
        projectId,
        BrainEntryType.VISION,
        "Vision carrying " + KEYED_PROBE,
        "What this is for, written down next to " + KEYED_PROBE,
        "test");
    brain.add(
        projectId,
        BrainEntryType.TECHNOLOGY,
        "Technology carrying " + KEYED_PROBE,
        "We deploy with " + KEYED_PROBE + " until the vault work lands",
        "test");
    brain.add(
        projectId,
        BrainEntryType.ERROR,
        "Error carrying " + KEYED_PROBE,
        "The run failed while using " + KEYED_PROBE,
        "test");
    brain.add(
        projectId,
        BrainEntryType.SOLUTION,
        "Solution carrying " + KEYED_PROBE,
        "Rotate " + KEYED_PROBE + " and stop pasting it into prose",
        "test");
    // A NOTE is denied by policy. It carries the marker as well as the probe, so a test can tell
    // "the item was refused" apart from "the item got in but was scrubbed".
    brain.add(
        projectId,
        BrainEntryType.NOTE,
        "Scratch note " + DENIED_MARKER,
        "Left over: SECRET=" + PROBE + " and " + DENIED_MARKER,
        "test");
    // A PROMPT_RESULT is denied too, for a different stated reason: it is the model's own output.
    brain.add(
        projectId,
        BrainEntryType.PROMPT_RESULT,
        "Model output " + DENIED_MARKER,
        "The model said to use " + KEYED_PROBE + ", " + DENIED_MARKER,
        "test");

    return new Planted(
        projects.requireReadable(projectId), phase, task, recorded.evidence().getId());
  }
}
