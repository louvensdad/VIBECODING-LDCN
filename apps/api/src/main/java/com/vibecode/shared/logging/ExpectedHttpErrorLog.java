package com.vibecode.shared.logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The operational signal for an HTTP error the caller caused, kept bounded.
 *
 * <p>This exists for one narrow population: errors that are <b>expected</b>. A 406 for an
 * {@code Accept} header nothing here can satisfy, a 415 for a body in the wrong media type, a 405
 * for a verb a route does not serve, a 404 for a path that does not exist. Every one of them is an
 * ordinary client mistake, every one of them is fully under the caller's control, and every one of
 * them is a request the server answered correctly. None of them is evidence of a fault in this
 * application, so none of them earns a stack trace.
 *
 * <p>Why that is not a detail: a stack trace per request, on a condition a client chooses by
 * setting one header, is log volume an outsider decides. The trace that started this — the WARN
 * from {@code ExceptionHandlerExceptionResolver} on {@code Accept: application/xml} — measured 189
 * frames on this codebase, written again for every repeat of the same request. That is not an
 * operator being informed; it is an operator's real failures being buried.
 *
 * <p><b>What this class is not.</b> It is not a filter, it is not a level, and it silences nothing.
 * A filter that matched on "looks like noise" would eventually match a genuine server fault, which
 * is a worse defect than the one it fixes. So the decision is made in one place with a name —
 * {@code ApiExceptionHandler} calls this only where it has already concluded the error is the
 * caller's — and an unexpected exception never reaches this class at all: it keeps the
 * {@code log.error(message, exception)} it always had, throwable attached, trace intact. A reviewer
 * checking whether a real 500 was quieted can answer it by reading the call sites of this class.
 *
 * <p><b>The signal that remains.</b> Silence would be the other way to get the noise down, and it
 * is the wrong one: an operator watching a 406 storm is watching a client misconfigured against
 * this API, and that is worth knowing. So every occurrence is counted, every occurrence writes one
 * line at DEBUG, and the count is announced at WARN on a logarithmic schedule — the 1st, 10th,
 * 100th, 1000th of a kind. A million bad requests produce seven WARN lines and no stack trace — 1, 10, 100, 1000,
 * 10000, 100000, 1000000 — and the first one still arrives immediately.
 *
 * <p><b>What is deliberately absent from every line.</b> The exception's message. Not because
 * these particular messages are known to be dangerous — "No acceptable representation" is not —
 * but because the messages of the population as a whole are not safe: a
 * {@code HttpMessageNotReadableException} carries a fragment of the request body, and the
 * {@code FieldError} inside a validation failure carries the rejected value in full, which is how
 * a password reached a log line here once already. A status and an exception type name are enough
 * to act on and cannot carry caller text. The counting key is built from those two things only.
 *
 * <p>The counter is per process and is never reset. It is a monotonic tally for the schedule
 * above, not a metric anyone should read a rate off.
 *
 * <p><b>The key space is capped rather than argued to be safe.</b> An earlier version of this
 * javadoc claimed the map could not grow without bound because status and exception type are drawn
 * from a fixed vocabulary. That was an assertion about today's {@code src/main}, not a property of
 * this class: {@code HttpStatusCode.valueOf} accepts any integer, an {@code ErrorResponse} may
 * carry any status, and review created nine distinct keys without difficulty. So the map now stops
 * at {@link #MAX_KINDS} and everything after that is tallied under one overflow key. Two
 * consequences worth stating: the tally degrades to a single bucket rather than to silence, and the
 * cap is approximate under concurrency — the size check and the insert are not one atomic step,
 * so several threads racing on unseen keys can leave a few entries above the cap. Bounded by the
 * number of threads, which is what the cap is there to guarantee; exact is not needed and would
 * cost a lock on the ordinary path.
 */
@Component
public class ExpectedHttpErrorLog {

  private static final Logger log = LoggerFactory.getLogger(ExpectedHttpErrorLog.class);

  /**
   * How many distinct kinds are tallied separately before the rest share one bucket.
   *
   * <p>Comfortably above the population this application can actually produce — review found
   * nine — and far below anything that could matter for memory.
   */
  static final int MAX_KINDS = 64;

  /** Where everything past the cap is counted, so the tally degrades rather than disappears. */
  static final String OVERFLOWED = "(other kinds)";

  private final Map<String, AtomicLong> occurrences = new ConcurrentHashMap<>();

  /**
   * Records one expected client error and returns how many of its kind this process has seen.
   *
   * @param status the HTTP status actually sent to the caller
   * @param kind a short fixed label for the cause — an exception type's simple name, never its
   *     message, and never anything derived from the request
   */
  public long record(int status, String kind) {
    String key = status + " " + kind;
    // MAX_KINDS - 1, because the overflow bucket takes a slot of its own: the cap is the size of
    // the whole map, not the number of kinds tallied separately plus one more entry nobody counted.
    if (!occurrences.containsKey(key) && occurrences.size() >= MAX_KINDS - 1) {
      key = OVERFLOWED;
    }
    long count = occurrences.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    // No throwable argument anywhere in this method: that is what keeps the stack trace out.
    log.debug("Expected client error {} (occurrence {})", key, count);
    if (isAnnounced(count)) {
      log.warn("Expected client error {}: {} so far in this process", key, count);
    }
    return count;
  }

  /** How many recorded past the cap, all sharing one bucket. Package-private, for the cap test. */
  long overflowCount() {
    AtomicLong count = occurrences.get(OVERFLOWED);
    return count == null ? 0 : count.get();
  }

  /** How many keys are being tallied separately. Package-private: the cap has to be checkable. */
  int distinctKinds() {
    return occurrences.size();
  }

  /** How many of one kind have been recorded. For tests and for anything that wants the tally. */
  public long countOf(int status, String kind) {
    AtomicLong count = occurrences.get(status + " " + kind);
    return count == null ? 0 : count.get();
  }

  /**
   * The logarithmic schedule: 1, 10, 100, 1000, and so on.
   *
   * <p>Package-private and tested directly, because "announced on a power of ten" is the property
   * that makes the WARN volume bounded and it should not be inferred from a storm of requests.
   */
  static boolean isAnnounced(long count) {
    if (count <= 0) {
      return false;
    }
    long step = 1;
    while (step < count && step <= Long.MAX_VALUE / 10) {
      step *= 10;
    }
    return step == count;
  }
}
