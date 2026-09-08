package com.vibecode.context.infrastructure.persistence;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored row of a {@link com.vibecode.context.domain.ContextPack} snapshot.
 *
 * <p>The row carries the item and the whole of its provenance — source type, source id, source
 * version, the project the source belonged to, and when that state was observed. Storing part of it
 * would leave an item nobody can trace back, and the domain refuses to build one of those.
 *
 * <p>{@code itemPosition} is written, not inferred. It is what makes the canonical order a fact of
 * the database rather than a hope about how a query happens to return rows.
 *
 * <p>{@code content} holds redacted text and there is no second copy of it here. Redaction runs
 * before anything is persisted, so this class has no field — and the table no column — that could
 * hold the value that was redacted away.
 */
@Entity
@Table(name = "context_pack_items")
public class ContextPackItemEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /**
   * The owning pack. Lazy, because loading an item is never a reason to load the rest of the
   * snapshot it came from.
   */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "pack_id", nullable = false)
  private ContextPackEntity pack;

  @Column(name = "item_position", nullable = false)
  private int itemPosition;

  /** {@link ContextItem#id()} — unique within the pack, and not beyond it. */
  @Column(name = "item_id", nullable = false, length = 200)
  private String itemId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 40)
  private ContextKind kind;

  /** 500, not 200: a label is human text and is displayed. See the column comment in V9. */
  @Column(name = "label", nullable = false, length = 500)
  private String label;

  @Column(name = "content", nullable = false, columnDefinition = "TEXT")
  private String content;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 40)
  private ContextSourceType sourceType;

  @Column(name = "source_id", nullable = false, length = 200)
  private String sourceId;

  /** Null where the underlying record has no revision — see {@link ContextSource#version()}. */
  @Column(name = "source_version")
  private Integer sourceVersion;

  @Column(name = "provenance_project_id", nullable = false)
  private UUID provenanceProjectId;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  /** For JPA only. */
  protected ContextPackItemEntity() {}

  ContextPackItemEntity(ContextPackEntity pack, int itemPosition, ContextItem item) {
    ContextProvenance provenance = item.provenance();
    this.id = UUID.randomUUID();
    this.pack = pack;
    this.itemPosition = itemPosition;
    this.itemId = item.id();
    this.kind = item.kind();
    this.label = item.label();
    this.content = item.content();
    this.sourceType = provenance.sourceType();
    this.sourceId = provenance.sourceId();
    this.sourceVersion = provenance.sourceVersion().orElse(null);
    this.provenanceProjectId = provenance.projectId();
    this.recordedAt = provenance.recordedAt();
  }

  /**
   * Rebuilds the domain item, provenance included.
   *
   * <p>Every domain invariant is re-checked on the way out, because a row edited by hand is exactly
   * the case where an item without provenance would otherwise slip back in.
   */
  public ContextItem toDomain() {
    ContextSource source = new ContextSource(sourceType, sourceId, sourceVersion);
    return new ContextItem(
        itemId, kind, label, content, new ContextProvenance(source, provenanceProjectId, recordedAt));
  }

  public UUID getId() {
    return id;
  }

  public int getItemPosition() {
    return itemPosition;
  }

  public String getItemId() {
    return itemId;
  }

  public ContextKind getKind() {
    return kind;
  }

  public String getLabel() {
    return label;
  }

  public String getContent() {
    return content;
  }

  public ContextSourceType getSourceType() {
    return sourceType;
  }

  public String getSourceId() {
    return sourceId;
  }

  public Integer getSourceVersion() {
    return sourceVersion;
  }

  public UUID getProvenanceProjectId() {
    return provenanceProjectId;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }

  /** Handles only. Never the content, which is context a log has no business copying. */
  @Override
  public String toString() {
    return "ContextPackItemEntity[id=" + id + ", position=" + itemPosition + ", item=" + itemId + "]";
  }
}
