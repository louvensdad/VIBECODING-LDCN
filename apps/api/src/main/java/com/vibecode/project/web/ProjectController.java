package com.vibecode.project.web;

import com.vibecode.project.application.ProjectService;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

  private final ProjectService projects;

  public ProjectController(ProjectService projects) {
    this.projects = projects;
  }

  @PostMapping
  public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
    ProjectResponse created =
        ProjectResponse.from(
            projects.create(request.name(), request.description(), request.originalIdea()));
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping
  public List<ProjectResponse> list() {
    return projects.listAccessible().stream().map(ProjectResponse::from).toList();
  }

  @GetMapping("/{id}")
  public ProjectResponse get(@PathVariable UUID id) {
    return ProjectResponse.from(projects.requireReadable(id));
  }
}
