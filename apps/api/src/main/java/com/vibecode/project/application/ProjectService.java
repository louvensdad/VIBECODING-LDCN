package com.vibecode.project.application;

import com.vibecode.project.domain.Project;
import com.vibecode.project.infrastructure.ProjectRepository;
import com.vibecode.shared.domain.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application entry point for the project module. */
@Service
@Transactional
public class ProjectService {

  private final ProjectRepository projects;

  public ProjectService(ProjectRepository projects) {
    this.projects = projects;
  }

  public Project create(String name, String description, String originalIdea) {
    return projects.save(new Project(name, description, originalIdea));
  }

  @Transactional(readOnly = true)
  public List<Project> list() {
    return projects.findAllByOrderByCreatedAtDesc();
  }

  @Transactional(readOnly = true)
  public Project get(UUID id) {
    return projects
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + id));
  }

  /**
   * Fails fast when a project does not exist. Used by other modules that hang data off a project
   * without needing the aggregate itself.
   */
  @Transactional(readOnly = true)
  public void requireExisting(UUID id) {
    if (!projects.existsById(id)) {
      throw new ResourceNotFoundException("Project not found: " + id);
    }
  }
}
