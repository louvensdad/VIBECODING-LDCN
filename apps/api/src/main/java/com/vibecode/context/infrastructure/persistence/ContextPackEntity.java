package com.vibecode.context.infrastructure.persistence;

import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextPack;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The stored form of a {@link ContextPack}.
 *
 * <p>A pack is written once and never edited. There is no setter, no {@code updated_at} and no
 * method that adds an item to a stored pack: a snapshot that could be amended afterwards would stop
 * being evidence of what context looked like and become a working set with a misleading name. A
 * changed selection is a new pack with a new id.
 *
 * <p><b>Identity is {@link #getId()}, the pack's UUID.</b> {@code contentFingerprint} is stored
 * beside it as description only — see {@link #getContentFingerprint()} — and nothing here or in
 * {@link ContextPackRepository} looks a pack up by it.
 */
@Entity
@Table(name = "context_packs")
public class ContextPackEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "task_reference", nullable = false, length = 500)
  private String taskReference;

  @Column(name = "assembled_at", nullable = false)
  private Instant assembledAt;

  @Column(name = "budget_max_items", nullable = false)
  private int budgetMaxItems;

  @Column(name = "budget_max_characters", nullable = false)
  private long budgetMaxCharacters;

  @Column(name = "budget_max_bytes", nullable = false)
  private long budgetMaxBytes;

  @Column(name = "content_fingerprint", nullable = false, length = 64)
  private String contentFingerprint;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /**
   * The items, in the order they were written.
   *
   * <p>Ordered by the stored {@code item_position} rather than left to the database. Hibernate is
   * free to return an unordered collection in any order it likes, and the reproducibility the
   * engine rests on must not depend on it choosing well.
   */
  @OneToMany(
      mappedBy = "pack",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = jakarta.persistence.FetchType.EAGER)
  @OrderBy("itemPosition ASC")
  private List<ContextPackItemEntity> items = new ArrayList<>();

  /** For JPA only. */
  protected ContextPackEntity() {}

  /**
   * Takes the snapshot as it stands.
   *
   * <p>The item order is the pack's own canonical order — {@link ContextPack} has already sorted
   * it, so the position written here is a copy of a decision the domain made, not one this class
   * makes.
   */
  public static ContextPackEntity from(ContextPack pack) {
    ContextPackEntity entity = new ContextPackEntity();
    entity.id = pack.packId();
    entity.projectId = pack.projectId();
    entity.taskReference = pack.taskReference();
    entity.assembledAt = pack.assembledAt();
    entity.budgetMaxItems = pack.budget().maxItems();
    entity.budgetMaxCharacters = pack.budget().maxCharacters();
    entity.budgetMaxBytes = pack.budget().maxBytes();
    entity.contentFingerprint = pack.contentFingerprint();
    entity.createdAt = Instant.now();

    List<ContextItem> packItems = pack.items();
    for (int position = 0; position < packItems.size(); position++) {
      entity.items.add(new ContextPackItemEntity(entity, position, packItems.get(position)));
    }
    return entity;
  }

  /**
   * Rebuilds the domain snapshot, and refuses to rebuild one whose stored order is wrong.
   *
   * <p>{@link ContextPack}'s constructor sorts whatever it is handed into canonical order, so on
   * its own it would <em>repair</em> a tampered {@code item_position} sequence rather than notice
   * it — and the repair would be invisible, because the fingerprint is computed after the sort.
   * Worse, {@link #getItems()} honours the stored order faithfully, so the two views of the same
   * pack would disagree and nothing would say so. The check below is what stops that: the stored
   * sequence is compared against the canonical one before the pack is built, and a mismatch is an
   * exception rather than a quiet correction.
   *
   * <p>Two different things make it fail, and the message names both because they call for
   * opposite responses. Either these rows were altered after the pack was written, or {@link
   * ContextItem#CANONICAL_ORDER} has changed since. <b>If the ordering rule is ever changed, every
   * previously stored pack stops loading — and that is the correct outcome.</b> A snapshot is a
   * record of what context looked like; silently re-sorting it under a rule invented afterwards
   * would rewrite the thing it claims to be a record of. The loud failure is the feature. The
   * answer then is a migration that decides explicitly what happens to packs written under the old
   * rule, not a relaxation of this check.
   *
   * <p>The pack's own constructor still re-validates everything else it owns — the budget above
   * all, so a pack edited into overrunning its ceiling is rejected here too.
   */
  public ContextPack toDomain() {
    List<ContextItem> storedOrder = new ArrayList<>(items.size());
    for (ContextPackItemEntity item : items) {
      storedOrder.add(item.toDomain());
    }

    List<ContextItem> canonicalOrder = new ArrayList<>(storedOrder);
    canonicalOrder.sort(ContextItem.CANONICAL_ORDER);
    if (!idsOf(storedOrder).equals(idsOf(canonicalOrder))) {
      throw new IllegalStateException(
          "Stored pack "
              + id
              + " is not in canonical order. Stored: "
              + idsOf(storedOrder)
              + ", canonical: "
              + idsOf(canonicalOrder)
              + ". Either the item_position values were altered after the pack was written, or"
              + " ContextItem.CANONICAL_ORDER has changed since — the second makes every pack"
              + " written under the old rule fail to load, which is intended: a snapshot must not"
              + " be silently re-sorted under a rule invented after it was taken.");
    }

    return new ContextPack(
        id,
        projectId,
        taskReference,
        assembledAt,
        new ContextBudget(budgetMaxItems, budgetMaxCharacters, budgetMaxBytes),
        storedOrder);
  }

  private static List<String> idsOf(List<ContextItem> items) {
    return items.stream().map(ContextItem::id).toList();
  }

  public UUID getId() {
    return id;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public String getTaskReference() {
    return taskReference;
  }

  public Instant getAssembledAt() {
    return assembledAt;
  }

  public ContextBudget getBudget() {
    return new ContextBudget(budgetMaxItems, budgetMaxCharacters, budgetMaxBytes);
  }

  /**
   * The digest as it was computed when this pack was written.
   *
   * <p>Description, never identity: two packs assembled from unchanged state share it on purpose,
   * and it changes for every pack ever stored if what the digest covers is changed. Comparing it to
   * another pack's is a legitimate use; finding a pack by it is not, and the repository offers no
   * way to.
   */
  public String getContentFingerprint() {
    return contentFingerprint;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** The stored items, in canonical order. Unmodifiable: a stored pack does not gain items. */
  public List<ContextPackItemEntity> getItems() {
    return List.copyOf(items);
  }

  /** Handles and counts. Never item content. */
  @Override
  public String toString() {
    return "ContextPackEntity[id="
        + id
        + ", project="
        + projectId
        + ", items="
        + items.size()
        + "]";
  }
}
