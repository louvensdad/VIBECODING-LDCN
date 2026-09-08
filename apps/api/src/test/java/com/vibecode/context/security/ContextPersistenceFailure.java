package com.vibecode.context.security;

import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextPolicyVersion;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.context.domain.RedactedContextItem;
import com.vibecode.context.infrastructure.persistence.ContextPackEntity;
import com.vibecode.context.infrastructure.persistence.ContextPackRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A write the database will refuse, arranged so the refusal carries a traceable string.
 *
 * <p>The whole point of a persistence-failure test is that the failure has to be real and it has to
 * be about a value one can then go looking for. The route used is a genuine asymmetry in the
 * design, not a contrivance: {@code context_pack_items.item_id} is {@code VARCHAR(200)} in V9,
 * {@code ContextItem.id} has no length rule at all, and nothing between the two checks. A 240
 * character item id therefore constructs cleanly through the domain, through admission, through the
 * compiled pack and through the entity, and is stopped for the first time by the INSERT.
 *
 * <p>Three columns share that shape — {@code item_id}, {@code source_id} and {@code policy_rule_id},
 * all 200 wide against unbounded domain fields, alongside {@code explanation} at 500, which V9's own
 * comments already acknowledge. {@code label} and {@code task_reference} are the two that do have
 * domain caps. No collector reaches 200 characters of identifier today, so this is a gap in the
 * guard rather than a live bug — but it is the guard the other two columns have and these do not.
 *
 * <p>Nothing here touches production code. It builds domain objects the ordinary way and hands the
 * result to the ordinary repository.
 */
final class ContextPersistenceFailure {

  /**
   * Carried inside the over-long identifier. Distinct from the compilation probe so a hit can be
   * attributed to the driver's error detail and to nothing else. Non-hex suffix, so no UUID can
   * produce it.
   */
  static final String OVERSIZED_IDENTIFIER_PROBE = "vc_context_sqlerror_probe_zqxw_604118";

  private ContextPersistenceFailure() {}

  /**
   * Writes a pack whose item id is 240 characters and contains {@link
   * #OVERSIZED_IDENTIFIER_PROBE}. The call always throws; what it throws is the point of the tests
   * that call it.
   */
  static void provoke(ContextPackRepository packs, UUID projectId) {
    String oversizedId =
        "item:" + OVERSIZED_IDENTIFIER_PROBE + ":" + "p".repeat(240);
    ContextItem item =
        new ContextItem(
            oversizedId,
            ContextKind.DECISION,
            "An item whose identifier is longer than the column that holds it",
            "Content that is entirely unremarkable and well inside every limit.",
            new ContextProvenance(
                ContextSource.of(ContextSourceType.BRAIN_ENTRY, UUID.randomUUID().toString()),
                projectId,
                Instant.parse("2026-01-01T00:00:00Z")));

    CompiledContextPack pack =
        new CompiledContextPack(
            UUID.randomUUID(),
            projectId,
            "CTX-09 oversized identifier",
            Instant.parse("2026-01-01T00:00:00Z"),
            new ContextBudget(10, 100_000L, 200_000L),
            ContextPolicyVersion.CURRENT,
            List.of(
                new AdmittedContextItem(
                    RedactedContextItem.rehydratedFromStorage(item),
                    ContextAdmission.allow(
                        "test.oversized-identifier",
                        "Built by a test to provoke a write the database refuses."))));

    packs.saveAndFlush(ContextPackEntity.from(pack));
  }
}
