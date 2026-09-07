package com.vibecode.brain.web;

import com.vibecode.brain.application.BrainService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/brain")
public class BrainController {

  private final BrainService brain;

  public BrainController(BrainService brain) {
    this.brain = brain;
  }

  @GetMapping
  public ProjectBrainResponse get(@PathVariable UUID projectId) {
    return ProjectBrainResponse.from(brain.load(projectId));
  }

  @PostMapping("/entries")
  public ResponseEntity<BrainEntryResponse> addEntry(
      @PathVariable UUID projectId, @Valid @RequestBody CreateBrainEntryRequest request) {
    BrainEntryResponse created =
        BrainEntryResponse.from(
            brain.add(
                projectId, request.type(), request.title(), request.content(), request.source()));
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }
}
