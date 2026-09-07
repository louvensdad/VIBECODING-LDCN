package com.vibecode.roadmap.web;

import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.Roadmap;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.roadmap.web.RoadmapDtos.CreatePhaseRequest;
import com.vibecode.roadmap.web.RoadmapDtos.MovePhaseRequest;
import com.vibecode.roadmap.web.RoadmapDtos.PhaseResponse;
import com.vibecode.roadmap.web.RoadmapDtos.RoadmapResponse;
import com.vibecode.task.application.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/roadmap")
public class RoadmapController {

  private final RoadmapService roadmaps;
  private final TaskService tasks;

  public RoadmapController(RoadmapService roadmaps, TaskService tasks) {
    this.roadmaps = roadmaps;
    this.tasks = tasks;
  }

  @PostMapping
  public ResponseEntity<RoadmapResponse> create(@PathVariable UUID projectId) {
    Roadmap roadmap = roadmaps.createOrGet(projectId);
    return ResponseEntity.status(HttpStatus.CREATED).body(assemble(projectId, roadmap));
  }

  @GetMapping
  public RoadmapResponse get(@PathVariable UUID projectId) {
    return assemble(projectId, roadmaps.require(projectId));
  }

  @PostMapping("/phases")
  public ResponseEntity<PhaseResponse> addPhase(
      @PathVariable UUID projectId, @Valid @RequestBody CreatePhaseRequest request) {
    RoadmapPhase phase =
        roadmaps.addPhase(projectId, request.position(), request.title(), request.description());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(PhaseResponse.from(phase, tasks.listByPhase(phase.getId())));
  }

  @PutMapping("/phases/{phaseId}/position")
  public RoadmapResponse movePhase(
      @PathVariable UUID projectId,
      @PathVariable UUID phaseId,
      @Valid @RequestBody MovePhaseRequest request) {
    roadmaps.movePhase(projectId, phaseId, request.position());
    return assemble(projectId, roadmaps.require(projectId));
  }

  private RoadmapResponse assemble(UUID projectId, Roadmap roadmap) {
    List<PhaseResponse> phases =
        roadmaps.listPhases(projectId).stream()
            .map(phase -> PhaseResponse.from(phase, tasks.listByPhase(phase.getId())))
            .toList();
    return RoadmapResponse.from(roadmap, phases);
  }
}
