package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import java.util.UUID;

/**
 * Reads one official source and reports, faithfully, what it holds.
 *
 * <p><b>Collection is not inclusion.</b> A collector produces <em>candidates</em>. It never decides
 * what belongs in a pack: it does not filter, rank, truncate, or merge two records because they say
 * similar things. Selection is deny-by-default and belongs to the compiler, under its own reviewable
 * policy. A collector that quietly dropped a record to anticipate that policy would make the policy
 * untestable — the item would already be gone before anyone could decide about it.
 *
 * <p>Every implementation is read-only and authorizes the project before reading anything hanging
 * off it. A project the caller does not own is reported as not found, never as an empty result: an
 * empty list would say "this project exists and has nothing", which is a fact about someone else's
 * data.
 *
 * <p>Implementations return items in a deterministic order and never consult a clock, a hash or
 * insertion order to decide it. Collecting twice from unchanged data yields the same items, in the
 * same sequence, with the same provenance.
 */
public interface ContextCollector {

  /** The source this collector speaks for. One collector per {@link ContextSourceType} constant. */
  ContextSourceType sourceType();

  /**
   * Every candidate this source currently holds for the project.
   *
   * <p>An empty list means the source has no record yet — no roadmap, no current task — and never
   * means a record was judged unworthy.
   *
   * @param projectId the project to read; must be readable by the caller
   * @param window bounds how many rows an unbounded source is asked for; see {@link
   *     ContextReadWindow}. Bounded sources ignore it.
   */
  List<ContextItem> collect(UUID projectId, ContextReadWindow window);
}
