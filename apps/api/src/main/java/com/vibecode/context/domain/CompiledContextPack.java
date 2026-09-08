package com.vibecode.context.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A finished pack together with the policy that produced it and the decision behind every item.
 *
 * <p>This is what the compiler produces and what persistence writes. {@link ContextPack} remains
 * the pack itself — the ordered, budgeted snapshot of items — and this type is that snapshot plus
 * the two things a pack cannot be honestly stored without: the version of the policy in force, and
 * the admission that let each item in.
 *
 * <p><b>Why it takes admitted items and nothing else.</b> There is no constructor here that accepts
 * a {@link ContextPack}, or a list of bare {@link ContextItem}s, or an admission map alongside a
 * pack that could disagree with it. The only way in is a list of {@link AdmittedContextItem}, each
 * of which already refuses to exist without an allow. So "a pack containing an item with no
 * admission" is not a case to test for; it is a sentence with no way to be written. {@link #pack()}
 * is derived from the admitted items rather than supplied, which is what closes the last gap — a
 * separately supplied pack could hold an item the admission list had never heard of.
 *
 * <p><b>Why this lives in the domain rather than beside the compiler.</b> Two reasons, and the
 * second is the one that decided it. First, an admission is a permanent part of what a stored pack
 * is: {@code ContextPackItemEntity} must write the rule id and the explanation, and a pack read
 * back years later must still carry them — see {@link ContextAdmission}. Second, persistence has to
 * name this type to store it, and infrastructure naming an application type would invert the
 * dependency that the application's own use of {@code ContextPackRepository} already establishes.
 * The compiler still owns the <em>decisions</em>; the domain owns the shape a decided pack has.
 *
 * <p><b>Determinism, stated exactly.</b> Two compilations of the same logical inputs do not produce
 * identical rows and never will: {@link #packId()} is a fresh UUID and {@link #assembledAt()} is a
 * clock reading. What they produce identically is {@link #canonicalPayload()} and therefore {@link
 * #packDigest()}. That is the claim this type makes, and it is the only one it can keep.
 *
 * @param packId the pack's persistent identity, and the only one
 * @param projectId the project the context describes; every item's provenance must agree
 * @param taskReference the task the pack was compiled for
 * @param assembledAt when the snapshot was taken; excluded from the digest on purpose
 * @param budget the ceiling selection was held to
 * @param policyVersion the policy in force when these items were admitted
 * @param admittedItems the selected items with their admissions; sorted into canonical order here,
 *     so the caller's order has no effect on the result
 */
public record CompiledContextPack(
    UUID packId,
    UUID projectId,
    String taskReference,
    Instant assembledAt,
    ContextBudget budget,
    ContextPolicyVersion policyVersion,
    List<AdmittedContextItem> admittedItems) {

  public CompiledContextPack {
    if (policyVersion == null) {
      throw new IllegalArgumentException(
          "A compiled pack must record the policy version it was compiled under, so a later reader"
              + " can explain why an old pack differs from a new one");
    }
    if (admittedItems == null) {
      throw new IllegalArgumentException("A compiled pack must be given its items, even if none");
    }

    List<AdmittedContextItem> ordered = new ArrayList<>(admittedItems.size());
    Set<String> seenIds = new HashSet<>();
    for (AdmittedContextItem admitted : admittedItems) {
      if (admitted == null) {
        throw new IllegalArgumentException("A compiled pack cannot hold a null item");
      }
      if (!seenIds.add(admitted.id())) {
        throw new IllegalArgumentException(
            "Duplicate item id in compiled pack: " + admitted.id());
      }
      ordered.add(admitted);
    }
    ordered.sort(AdmittedContextItem.CANONICAL_ORDER);
    admittedItems = List.copyOf(ordered);

    // Everything else a pack must satisfy — the project agreeing with every item's provenance, the
    // budget, the ordering — is ContextPack's to enforce, and it does so on the items this record
    // was just handed. Building it here rather than trusting a caller to build a matching one is
    // what makes pack() and admittedItems() incapable of disagreeing.
    new ContextPack(packId, projectId, taskReference, assembledAt, budget, itemsOf(admittedItems));
  }

  /**
   * The pack itself, without the admissions.
   *
   * <p>Rebuilt on each call rather than cached in a field: a record's components are its equality,
   * and a cached pack would be a second copy of the same items that a future edit could let drift.
   * {@link ContextPack} sorts by the same comparator this record already applied, so the two orders
   * agree by construction.
   */
  public ContextPack pack() {
    return new ContextPack(
        packId, projectId, taskReference, assembledAt, budget, itemsOf(admittedItems));
  }

  /** What this pack actually costs. Counted over the redacted content, which is what would leave. */
  public ContextUsage usage() {
    ContextUsage usage = ContextUsage.EMPTY;
    for (AdmittedContextItem admitted : admittedItems) {
      usage = usage.plus(admitted.item());
    }
    return usage;
  }

  public int size() {
    return admittedItems.size();
  }

  public boolean isEmpty() {
    return admittedItems.isEmpty();
  }

  /** The canonical, storage-free representation — see {@link CanonicalContextPackPayload}. */
  public CanonicalContextPackPayload canonicalPayload() {
    return CanonicalContextPackPayload.of(
        policyVersion, projectId, taskReference, budget, admittedItems);
  }

  /**
   * The digest of {@link #canonicalPayload()}: 64 lowercase hex characters of SHA-256.
   *
   * <p>Distinct from {@link ContextPack#contentFingerprint()} and not a substitute for it. The
   * fingerprint covers one pack's item text under a definition fixed before policy existed; this
   * covers the whole compilation — policy version, budget, order, and per item the rule that
   * admitted it. Neither is an identity and neither may become one: identity is {@link #packId()}.
   */
  public String packDigest() {
    return canonicalPayload().digest();
  }

  private static List<ContextItem> itemsOf(List<AdmittedContextItem> admitted) {
    return admitted.stream().map(AdmittedContextItem::item).toList();
  }

  /** Handles and counts. Never item content, and never the payload. */
  @Override
  public String toString() {
    return "CompiledContextPack[packId="
        + packId
        + ", project="
        + projectId
        + ", policy="
        + policyVersion
        + ", items="
        + admittedItems.size()
        + "]";
  }
}
