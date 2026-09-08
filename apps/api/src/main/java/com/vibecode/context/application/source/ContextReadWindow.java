package com.vibecode.context.application.source;

/**
 * How many recent records a collector may read from a source that grows without bound.
 *
 * <p><b>This is a query bound, not a selection decision.</b> Evidence and output analyses accumulate
 * for the life of a project; a collector that read every row would grow slower and heavier with no
 * ceiling. The window says "ask the database for at most this many of the most recent rows" and
 * nothing else. It does not rank, it does not judge relevance, and it is not a stand-in for policy:
 * what enters a pack is decided later, deny-by-default, by the selection step. Everything the window
 * returns is emitted as a candidate.
 *
 * <p>Sources that are bounded by their own nature — the project row, the roadmap, the current phase
 * and task, that task's acceptance criteria — ignore the window entirely. Applying it there would
 * turn a bound into a filter.
 *
 * <p>The window is a parameter of every collect call rather than a field read from configuration, so
 * that a caller who narrows it has said so at the call site and a reader of a collected result can
 * see which bound produced it.
 *
 * @param recentRecords the maximum number of most-recent rows to read per unbounded source
 */
public record ContextReadWindow(int recentRecords) {

  /**
   * The window used when a caller does not name one.
   *
   * <p>Fifty is large enough that an ordinary project's whole evidence trail fits inside it, so the
   * bound is invisible in practice, and small enough that a long-lived project cannot make a
   * collection unboundedly expensive. It is a pragmatic ceiling, not a claim about relevance.
   */
  public static final ContextReadWindow DEFAULT = new ContextReadWindow(50);

  public ContextReadWindow {
    if (recentRecords < 1) {
      throw new IllegalArgumentException(
          "A read window must allow at least one record, got: " + recentRecords);
    }
  }
}
