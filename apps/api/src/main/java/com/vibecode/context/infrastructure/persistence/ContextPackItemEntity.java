package com.vibecode.context.infrastructure.persistence;

import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.context.domain.RedactedContextItem;
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
 * hold the value that was redacted away. That ordering is now carried by the types as well as by
 * the pipeline: the constructor below takes an {@link AdmittedContextItem}, which cannot be built
 * from anything but a {@link RedactedContextItem}, so no caller can hand this class a raw string to
 * store.
 *
 * <p>The row also carries the admission that let the item in: the id of the policy rule and that
 * rule's explanation, both mandatory. They are stored rather than re-derived because a pack is a
 * historical snapshot - asking today's policy why an item was admitted two versions ago would
 * produce an answer that sounds authoritative and is about a different set of rules. There is
 * deliberately no decision column: a denied item is never written, so every row here is an allow by
 * construction, and a column saying so on every row would carry no information.
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

  /** The rule that admitted this item, as it was named at the time. See the class javadoc. */
  @Column(name = "policy_rule_id", nullable = false, length = 200)
  private String policyRuleId;

  /** That rule's explanation, as it read at the time. Human text, so 500 - see the V9 comments. */
  @Column(name = "explanation", nullable = false, length = 500)
  private String explanation;

  /** For JPA only. */
  protected ContextPackItemEntity() {}

  /**
   * Takes an {@link AdmittedContextItem} and not a bare {@link ContextItem}, which is the whole
   * point: there is no constructor here that can build a row without an admission, so an item
   * cannot reach the database with nobody able to say why it is there.
   */
  ContextPackItemEntity(ContextPackEntity pack, int itemPosition, AdmittedContextItem admitted) {
    ContextItem item = admitted.item();
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
    this.policyRuleId = admitted.admission().policyRuleId();
    this.explanation = admitted.admission().explanation();
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

  /**
   * Rebuilds the item together with the decision that admitted it.
   *
   * <p>Rehydration is a different boundary from creation, and {@link
   * RedactedContextItem#rehydratedFromStorage(ContextItem)} is named so nobody has to guess which
   * one this is. Nothing re-redacts here. The content in this row was redacted before it was
   * written — there is no column that could have held the raw value, and the only write path is
   * one whose types demand a redacted item — so what comes back is trusted because we wrote it, not
   * because it was checked. That second clause is a claim about production code held by a build
   * rule, not by the schema: for one commit a method reference in the compiler package was a write
   * path that filled exactly this column with raw text, and the dependency allowlist in {@code
   * ContextModuleArchitectureTest} is what closed it.
   *
   * <p>Re-running the redactor on read would be worse: the stored digest was taken over the text as
   * written, and a redactor whose patterns had widened since would hand back a pack that no longer
   * matched its own digest.
   *
   * <p>The admission comes back as an allow because that is what a stored row is: a denied item was
   * never written. {@link ContextAdmission} re-checks that the rule id and the explanation are both
   * present, so a row edited to drop either of them fails to load rather than coming back as an item
   * nobody can account for.
   */
  public AdmittedContextItem toAdmitted() {
    return new AdmittedContextItem(
        RedactedContextItem.rehydratedFromStorage(toDomain()),
        ContextAdmission.allow(policyRuleId, explanation));
  }

  public UUID getId() {
    return id;
  }

  /** The rule that admitted this item when the pack was compiled. Never re-derived on read. */
  public String getPolicyRuleId() {
    return policyRuleId;
  }

  /** That rule's explanation as it read at the time. */
  public String getExplanation() {
    return explanation;
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
