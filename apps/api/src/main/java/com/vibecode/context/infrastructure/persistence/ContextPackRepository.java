package com.vibecode.context.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
