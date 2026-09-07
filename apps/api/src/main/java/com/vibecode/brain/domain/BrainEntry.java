package com.vibecode.brain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One unit of official project memory.
 *
 * <p>Entries are append-only. {@code version} exists so a later step can supersede an entry with a
 * newer revision instead of mutating history.
 */
@Entity
@Table(name = "brain_entries")
public class BrainEntry {

  private static final int FIRST_VERSION = 1;

  @Id private UUID id;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private BrainEntryType type;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  /**
   * Where the knowledge came from — a human, a named model, a build. Provenance is what lets the
   * user judge whether memory is trustworthy.
   */
  @Column(nullable = false, length = 80)
  private String source;

  @Column(nullable = false)
  private int version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** For JPA only. */
  protected BrainEntry() {}

  public BrainEntry(
      UUID projectId, BrainEntryType type, String title, String content, String source) {
    this.id = UUID.randomUUID();
    this.projectId = projectId;
    this.type = type;
    this.title = title;
    this.content = content;
    this.source = source;
    this.version = FIRST_VERSION;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public BrainEntryType getType() {
    return type;
  }

  public String getTitle() {
    return title;
  }

  public String getContent() {
    return content;
  }

  public String getSource() {
    return source;
  }

  public int getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
