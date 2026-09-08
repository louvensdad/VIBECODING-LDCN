package com.vibecode.context.application.compiler;

import com.vibecode.context.application.source.ContextReadWindow;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.infrastructure.persistence.ContextPackEntity;
import com.vibecode.context.infrastructure.persistence.ContextPackRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compiles a pack and stores it, in that order and in one transaction.
 *
 * <p>Separate from {@link ContextPackCompiler} on purpose. The compiler is where the decisions
 * happen and it touches no writable store, so it can be reasoned about — and tested — without a
 * database in the way. This class is the two-line seam where a decided pack becomes a row.
 *
 * <p>Nothing is transformed here. The digest, the policy version and every item's admission are
 * copied from the compiled pack exactly as it produced them; persistence derives none of them.
 * A digest computed at write time would be a digest of whatever the entity happened to hold, which
 * is a different claim from the one the compiler makes.
 *
 * <p>There is no update path and there will not be one. A pack is written once; a changed selection
 * is a new pack with a new id, and the old one stays as the record of what was actually put in
 * front of the work at the time.
 */
@Service
public class ContextPackAssembler {

  private final ContextPackCompiler compiler;
  private final ContextPackRepository packs;

  public ContextPackAssembler(ContextPackCompiler compiler, ContextPackRepository packs) {
    this.compiler = compiler;
    this.packs = packs;
  }

  /** Compiles and stores, with the default read window. */
  @Transactional
  public CompiledContextPack assemble(UUID projectId, String taskReference, ContextBudget budget) {
    return assemble(projectId, taskReference, budget, ContextReadWindow.DEFAULT);
  }

  /**
   * Compiles the pack for one task and writes it.
   *
   * @return the compiled pack as it was stored — the same object the digest was taken over, not a
   *     re-read of the row
   */
  @Transactional
  public CompiledContextPack assemble(
      UUID projectId, String taskReference, ContextBudget budget, ContextReadWindow window) {
    CompiledContextPack compiled = compiler.compile(projectId, taskReference, budget, window);
    packs.save(ContextPackEntity.from(compiled));
    return compiled;
  }
}
