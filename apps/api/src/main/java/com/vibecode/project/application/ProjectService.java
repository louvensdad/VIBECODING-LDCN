package com.vibecode.project.application;

import com.vibecode.identity.application.SecurityEventLogger;
import com.vibecode.identity.domain.CurrentUser;
import com.vibecode.identity.domain.CurrentUserProvider;
import com.vibecode.project.domain.Project;
import com.vibecode.project.infrastructure.ProjectRepository;
import com.vibecode.shared.domain.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application entry point for the project module, and the choke point for project authorization.
 *
 * <p>Every module that hangs data off a project — brain, roadmap, task, evidence, prompts — reaches
 * a project through {@link #requireReadable} or {@link #requireWritable}. That is deliberate: a
 * single gate is one thing to audit, whereas a check per controller is a checklist to forget.
 *
 * <p>A project the caller may not see is reported as <em>not found</em>, not as forbidden. A 403
 * would confirm the id belongs to a real project, which is exactly the fact an attacker probing
 * UUIDs is trying to learn.
 */
@Service
@Transactional
public class ProjectService {

  private final ProjectRepository projects;
  private final ProjectAccessPolicy accessPolicy;
  private final CurrentUserProvider currentUserProvider;
  private final SecurityEventLogger securityEvents;

  public ProjectService(
      ProjectRepository projects,
      ProjectAccessPolicy accessPolicy,
      CurrentUserProvider currentUserProvider,
      SecurityEventLogger securityEvents) {
    this.projects = projects;
    this.accessPolicy = accessPolicy;
    this.currentUserProvider = currentUserProvider;
    this.securityEvents = securityEvents;
  }

  /** Creates a project owned by whoever is signed in. */
  public Project create(String name, String description, String originalIdea) {
    CurrentUser owner = currentUserProvider.require();
    return projects.save(new Project(owner.id(), name, description, originalIdea));
  }

  /** Only the caller's own projects. The filter is a query, never a post-filter in the client. */
  @Transactional(readOnly = true)
  public List<Project> listAccessible() {
    CurrentUser user = currentUserProvider.require();
    return projects.findByOwnerUserIdOrderByCreatedAtDesc(user.id());
  }

  /** The project, if the caller may read it. */
  @Transactional(readOnly = true)
  public Project requireReadable(UUID id) {
    return require(id, AccessLevel.READ);
  }

  /** The project, if the caller may change its content. */
  @Transactional(readOnly = true)
  public Project requireWritable(UUID id) {
    return require(id, AccessLevel.WRITE);
  }

  /** The project, if the caller may change the project itself. */
  @Transactional(readOnly = true)
  public Project requireManageable(UUID id) {
    return require(id, AccessLevel.MANAGE);
  }

  private enum AccessLevel {
    READ,
    WRITE,
    MANAGE
  }

  private Project require(UUID id, AccessLevel level) {
    CurrentUser user = currentUserProvider.require();
    Project project = projects.findById(id).orElse(null);

    boolean allowed =
        project != null
            && switch (level) {
              case READ -> accessPolicy.canRead(user, project);
              case WRITE -> accessPolicy.canWrite(user, project);
              case MANAGE -> accessPolicy.canManage(user, project);
            };

    if (!allowed) {
      // Logged so probing is visible, then reported as if the project simply did not exist.
      if (project != null) {
        securityEvents.accessDenied(user.id(), "project", id);
      }
      throw new ResourceNotFoundException("Project not found: " + id);
    }
    return project;
  }
}
