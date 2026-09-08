package com.vibecode.context.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Stored context packs, found by identity and by project.
 *
 * <p><b>There is deliberately no {@code findByContentFingerprint}.</b> The fingerprint describes
 * what a pack says, not which pack it is: two packs assembled from unchanged state share one, so a
 * lookup by it would return an arbitrary member of a set and read like it had returned "the" pack.
 * A pack is found by its id, and by nothing else.
 */
@Repository
public interface ContextPackRepository extends JpaRepository<ContextPackEntity, UUID> {

  /** Most recent snapshot first, which is the order every reader of a history actually wants. */
  List<ContextPackEntity> findByProjectIdOrderByAssembledAtDesc(UUID projectId);

  /**
   * Scoped by project as well as by id, so a caller that already knows which project it is acting
   * for cannot accidentally read a pack belonging to another one.
   */
  Optional<ContextPackEntity> findByIdAndProjectId(UUID id, UUID projectId);

  /**
   * One bounded page of pack ids, newest first.
   *
   * <p><b>Why ids and not entities.</b> Packs are append-only — a second compile over unchanged
   * state produces a second pack rather than replacing the first — so any query that returns every
   * pack in a project grows without ceiling for the life of that project. This one takes a {@link
   * Pageable} so the caller must say how many it wants, and it selects ids only so the page can be
   * decided by the database rather than in memory. A {@code join fetch} combined with pagination
   * would force Hibernate to read the whole collection and paginate it in the application, which is
   * the cost this method exists to avoid; {@link #findAllWithItemsByIdIn} is the second half.
   *
   * <p><b>The ordering has three keys and only the first is a claim about age.</b>
   * {@code assembledAt} is what "newest first" means. {@code createdAt} — the moment the row was
   * written, taken from the wall clock rather than the application's injected one — separates two
   * packs that share an assembly instant. {@code id} is last and breaks a remaining tie
   * arbitrarily but <em>stably</em>, so a client rendering a list does not see it reshuffle between
   * two reads of unchanged data.
   *
   * <p>A tiebreaker is not a nicety here. {@code assembled_at} is microsecond precision on both
   * engines, and under a frozen clock every pack in a project shares one instant — at which point a
   * sort on that column alone leaves the order entirely to the database, which returned insertion
   * order: the exact reverse of the guarantee this method's name makes.
   */
  @Query(
      "select p.id from ContextPackEntity p where p.projectId = :projectId"
          + " order by p.assembledAt desc, p.createdAt desc, p.id desc")
  List<UUID> findPackIdsByProjectNewestFirst(
      @Param("projectId") UUID projectId, Pageable pageable);

  /**
   * The named packs with their items already loaded, in one statement.
   *
   * <p>{@code ContextPackEntity.items} is {@code EAGER}, which is right for reading one pack and
   * quietly wrong for reading many: a collection query issues one further statement per pack, so
   * forty packs cost forty-one. Fetching the collection explicitly makes the cost constant instead
   * — measured at 42 statements before and a small constant after, for the same forty packs.
   *
   * <p>Returns the packs in no particular order. Ordering a fetch-joined query by a column of the
   * root would order the joined rows rather than the roots, so the caller re-imposes the order of
   * the ids it asked for — which it already has from {@link #findPackIdsByProjectNewestFirst}, and
   * which is the order it must honour anyway.
   *
   * <p>Item order within a pack is not left to this query either: the collection carries {@code
   * @OrderBy("itemPosition ASC")}, and {@code ContextPackEntity.toCompiled} refuses to rebuild a
   * pack whose stored order is not the canonical one — so a fetch join that returned items in some
   * other order would fail loudly here rather than produce a quietly re-sorted snapshot.
   */
  @Query("select p from ContextPackEntity p left join fetch p.items where p.id in :ids")
  List<ContextPackEntity> findAllWithItemsByIdIn(@Param("ids") Collection<UUID> ids);
}
