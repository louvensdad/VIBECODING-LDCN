package com.vibecode.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The project the user is being guided through.
 *
 * <p>A Project owns identity and lifecycle only. The knowledge about the project — vision,
 * decisions, current state — belongs to the Project Brain, never to this entity.
 */
@Entity
@Table(name = "projects")
public class Project {

  /** Phase every project starts in, before a roadmap is recorded. */
  public static final String INITIAL_PHASE = "Foundation";

  @Id private UUID id;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(length = 1000)
  private String description;

  @Column(name = "original_idea", nullable = false, columnDefinition = "TEXT")
  private String originalIdea;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private ProjectStatus status;

  @Column(name = "current_phase", length = 100)
  private String currentPhase;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** For JPA only. */
  protected Project() {}

  public Project(String name, String description, String originalIdea) {
    this.id = UUID.randomUUID();
    this.name = name;
    this.description = description;
    this.originalIdea = originalIdea;
    this.status = ProjectStatus.ACTIVE;
    this.currentPhase = INITIAL_PHASE;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public void changeStatus(ProjectStatus newStatus) {
    this.status = newStatus;
    touch();
  }

  public void moveToPhase(String phase) {
    this.currentPhase = phase;
    touch();
  }

  private void touch() {
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getOriginalIdea() {
    return originalIdea;
  }

  public ProjectStatus getStatus() {
    return status;
  }

  public String getCurrentPhase() {
    return currentPhase;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
