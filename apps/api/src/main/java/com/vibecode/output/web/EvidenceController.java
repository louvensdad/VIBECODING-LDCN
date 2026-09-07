package com.vibecode.output.web;

import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.application.EvidenceService.EvidenceRecorded;
import com.vibecode.output.domain.EvidenceType;
import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.domain.OutputAnalysisStatus;
import com.vibecode.output.domain.TaskEvidence;
import com.vibecode.task.domain.TaskStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Records evidence against a task and returns what it changed.
 *
 * <p>Distinct from {@code POST /outputs/analyze}, which analyses a snippet without storing anything
 * or touching a task.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/tasks/{taskId}/evidence")
public class EvidenceController {

  private final EvidenceService evidence;

  public EvidenceController(EvidenceService evidence) {
    this.evidence = evidence;
  }

  @PostMapping
  public ResponseEntity<EvidenceRecordedResponse> record(
      @PathVariable UUID projectId,
      @PathVariable UUID taskId,
      @Valid @RequestBody RecordEvidenceRequest request) {
    EvidenceRecorded recorded =
        evidence.record(
            projectId, taskId, request.type(), request.rawContent(), request.source());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(EvidenceRecordedResponse.from(recorded));
  }

  @GetMapping
  public List<EvidenceResponse> list(@PathVariable UUID projectId, @PathVariable UUID taskId) {
    return evidence.listForTask(projectId, taskId).stream()
        .map(item -> EvidenceResponse.from(item, evidence.analysisOf(item.getId()).orElse(null)))
        .toList();
  }

  public record RecordEvidenceRequest(
      @NotNull EvidenceType type,
      @NotBlank @Size(max = 200_000) String rawContent,
      @NotBlank @Size(max = 80) String source) {}

  public record AnalysisResponse(
      OutputAnalysisStatus status,
      String summary,
      List<String> signals,
      boolean shouldContinue,
      boolean requiresCorrection) {

    static AnalysisResponse from(OutputAnalysisRecord record) {
      return new AnalysisResponse(
          record.getStatus(),
          record.getSummary(),
          record.getSignalNames(),
          record.isShouldContinue(),
          record.isRequiresCorrection());
    }
  }

  public record EvidenceResponse(
      UUID id,
      UUID taskId,
      EvidenceType type,
      String rawContent,
      String source,
      Instant createdAt,
      AnalysisResponse analysis) {

    static EvidenceResponse from(TaskEvidence item, OutputAnalysisRecord analysis) {
      return new EvidenceResponse(
          item.getId(),
          item.getTaskId(),
          item.getType(),
          item.getRawContent(),
          item.getSource(),
          item.getCreatedAt(),
          analysis == null ? null : AnalysisResponse.from(analysis));
    }
  }

  /** What recording the evidence did: the verdict, the resulting task status, what is missing. */
  public record EvidenceRecordedResponse(
      EvidenceResponse evidence,
      AnalysisResponse analysis,
      TaskStatus taskStatus,
      boolean taskCompleted,
      List<String> missingForCompletion) {

    static EvidenceRecordedResponse from(EvidenceRecorded recorded) {
      return new EvidenceRecordedResponse(
          EvidenceResponse.from(recorded.evidence(), recorded.analysis()),
          AnalysisResponse.from(recorded.analysis()),
          recorded.task().getStatus(),
          recorded.task().getStatus() == TaskStatus.COMPLETED,
          recorded.completion() == null ? List.of() : recorded.completion().missing());
    }
  }
}
