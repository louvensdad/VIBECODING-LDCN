package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextSourceType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the official records currently hold for a project, as candidates.
 *
 * <p><b>This is not a pack, and building one is not this class's job.</b> What comes out is the
 * complete, unfiltered set of candidates: every source that has something to say has said it here.
 * Deciding which of them a reader actually gets is the selection step's job, under a deny-by-default
 * policy that can be read and argued with. Treating this result as a pack would silently make
 * "everything that exists" the policy.
 *
 * <p>Every {@link ContextSourceType} constant has exactly one collector, checked at startup. A
 * source with no collector would be a hole nothing reports: a pack missing a whole category of
 * official state, with no error anywhere to say so.
 *
 * <p>The result is sorted by {@code ContextItem.CANONICAL_ORDER}, which is total here because item
 * ids are unique across collectors. So the order does not depend on the order Spring happened to
 * inject the collectors in, and two collections of unchanged data are identical.
 */
@Service
@Transactional(readOnly = true)
public class CandidateContextCollection {

  private final Map<ContextSourceType, ContextCollector> collectors =
      new EnumMap<>(ContextSourceType.class);

  public CandidateContextCollection(List<ContextCollector> collectors) {
    for (ContextCollector collector : collectors) {
      ContextCollector previous = this.collectors.put(collector.sourceType(), collector);
      if (previous != null) {
        throw new IllegalStateException(
            "Two collectors claim the same source, so one of them would never be asked: "
                + collector.sourceType());
      }
    }
    List<ContextSourceType> missing =
        java.util.Arrays.stream(ContextSourceType.values())
            .filter(type -> !this.collectors.containsKey(type))
            .toList();
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Every context source needs a collector; these have none: " + missing);
    }
  }

  /** Every candidate the project's records hold, read with the default window. */
  public List<ContextItem> collect(UUID projectId) {
    return collect(projectId, ContextReadWindow.DEFAULT);
  }

  /**
   * Every candidate the project's records hold.
   *
   * <p>Throws not-found if the caller may not read the project — the first collector to run
   * authorizes, and so does every one after it.
   *
   * @param window bounds how many rows the unbounded sources are asked for; see {@link
   *     ContextReadWindow}. It is a query bound, never a selection rule.
   */
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    return java.util.Arrays.stream(ContextSourceType.values())
        .map(collectors::get)
        .flatMap(collector -> collector.collect(projectId, window).stream())
        .sorted(ContextItem.CANONICAL_ORDER)
        .toList();
  }

  /**
   * The candidates of one source alone, for a caller — or a test — that wants just that source.
   *
   * <p>Returned in the collector's own order, untouched. Re-sorting here would hide a collector that
   * had stopped being deterministic behind an order imposed afterwards.
   */
  public List<ContextItem> collectFrom(
      ContextSourceType sourceType, UUID projectId, ContextReadWindow window) {
    return collectors.get(sourceType).collect(projectId, window);
  }
}
