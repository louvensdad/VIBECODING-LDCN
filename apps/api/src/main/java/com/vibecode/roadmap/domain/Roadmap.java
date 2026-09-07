package com.vibecode.roadmap.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The planned path of a project. One roadmap per project.
 *
 * <p>The roadmap itself holds no progress figures: everything measurable is derived from the tasks
 * in its phases, so there is nothing here that can drift out of sync with them.
 */
@Entity
@Table(name = "roadmaps")
public class Roadmap {

  @Id private UUID id;

  @Column(name = "project_id", nullable = false, unique = true)
  private UUID projectId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** For JPA only. */
  protected Roadmap() {}

  public Roadmap(UUID projectId) {
    this.id = UUID.randomUUID();
    this.projectId = projectId;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
