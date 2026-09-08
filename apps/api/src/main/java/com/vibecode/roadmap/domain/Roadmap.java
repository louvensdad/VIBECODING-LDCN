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

  /**
   * Records that the shape of the plan changed: today that is a phase added or reordered, the
   * only two structural operations the service offers. Removal belongs here too, when it exists.
   *
   * <p>Called by {@code RoadmapService}, which is the only thing that can see a structural change
   * happen — the roadmap row holds no phases of its own, so the entity cannot notice one by itself.
   * A {@code @PreUpdate} hook would be no help either: nothing on this row is written when a phase
   * appears, so there is no update for JPA to intercept.
   *
   * <p><b>Deliberately not "something below me changed".</b> A phase's own status being recomputed
   * or its title edited is the phase's business and is carried by {@code RoadmapPhase.updatedAt}. If
   * this instant moved for those too it would stop distinguishing anything and would only be a
   * different kind of lie than the one it used to tell, when it was written once in the constructor
   * and never again.
   */
  public void recordStructuralChange() {
    this.updatedAt = Instant.now();
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
