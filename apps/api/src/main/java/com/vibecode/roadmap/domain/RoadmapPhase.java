package com.vibecode.roadmap.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An ordered stage of a roadmap: "Setup", "Authentication", "Dashboard". */
@Entity
@Table(name = "roadmap_phases")
public class RoadmapPhase {

  @Id private UUID id;

  @Column(name = "roadmap_id", nullable = false)
  private UUID roadmapId;

  /** 1-based order within the roadmap. Unique per roadmap, enforced by the schema. */
  @Column(nullable = false)
  private int position;

  @Column(nullable = false, length = 160)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private PhaseStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** For JPA only. */
  protected RoadmapPhase() {}

  public RoadmapPhase(UUID roadmapId, int position, String title, String description) {
    this.id = UUID.randomUUID();
    this.roadmapId = roadmapId;
    this.position = position;
    this.title = title;
    this.description = description;
    this.status = PhaseStatus.PLANNED;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  /** Called only by the status calculator, which derives phase status from its tasks. */
  public void applyDerivedStatus(PhaseStatus derived) {
    if (this.status != derived) {
      this.status = derived;
      this.updatedAt = Instant.now();
    }
  }

  public void moveTo(int newPosition) {
    this.position = newPosition;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getRoadmapId() {
    return roadmapId;
  }

  public int getPosition() {
    return position;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public PhaseStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
