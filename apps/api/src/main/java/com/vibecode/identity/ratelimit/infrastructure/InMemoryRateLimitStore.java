package com.vibecode.identity.ratelimit.infrastructure;

import com.vibecode.identity.ratelimit.domain.RateLimitKey;
import com.vibecode.identity.ratelimit.domain.RateLimitPolicy;
import com.vibecode.identity.ratelimit.domain.RateLimitStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token buckets held in this process.
 *
 * <p>Bounded on purpose. An attacker can invent an unlimited number of identifiers, and a plain
 * {@code ConcurrentHashMap} would grow with every one of them until the process died — turning the
 * defence into the attack. This keeps at most {@code maxEntries} buckets, evicting the
 * least-recently-used, and drops buckets that have been idle long enough to be full again anyway.
 *
 * <p>Eviction is safe in the direction that matters: a discarded bucket is one that had gone
 * unused, so recreating it full is the same answer it would have given.
 *
 * <p>Every operation is synchronized on the map. The whole check-and-consume must be one atomic
 * step or two concurrent requests could both take the last token; a lock here costs nothing next
 * to the password hashing it protects.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

  private final Map<RateLimitKey, Bucket> buckets;
  private final int maxEntries;

  public InMemoryRateLimitStore(int maxEntries) {
    if (maxEntries < 1) {
      throw new IllegalArgumentException("The store must be allowed at least one bucket");
    }
    this.maxEntries = maxEntries;
    this.buckets =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<RateLimitKey, Bucket> eldest) {
            return size() > InMemoryRateLimitStore.this.maxEntries;
          }
        };
  }

  @Override
  public synchronized boolean tryConsume(RateLimitKey key, RateLimitPolicy policy, Instant now) {
    evictIdle(policy, now);
    Bucket bucket = buckets.computeIfAbsent(key, ignored -> Bucket.full(policy, now));
    return bucket.tryConsume(policy, now);
  }

  @Override
  public synchronized void reset(RateLimitKey key) {
    buckets.remove(key);
  }

  @Override
  public synchronized int size() {
    return buckets.size();
  }

  /**
   * Drops buckets idle for longer than the policy needs to refill them.
   *
   * <p>Runs over the map only when it has grown past half its bound, so the common case stays a
   * single map lookup.
   */
  private void evictIdle(RateLimitPolicy policy, Instant now) {
    if (buckets.size() < maxEntries / 2) {
      return;
    }
    long cutoff = now.minus(policy.idleRetention()).toEpochMilli();
    buckets.entrySet().removeIf(entry -> entry.getValue().lastTouchedMillis < cutoff);
  }

  /** A token bucket that refills continuously rather than resetting at a window boundary. */
  private static final class Bucket {

    private double tokens;
    private long lastTouchedMillis;

    private Bucket(double tokens, long lastTouchedMillis) {
      this.tokens = tokens;
      this.lastTouchedMillis = lastTouchedMillis;
    }

    static Bucket full(RateLimitPolicy policy, Instant now) {
      return new Bucket(policy.capacity(), now.toEpochMilli());
    }

    boolean tryConsume(RateLimitPolicy policy, Instant now) {
      refill(policy, now);
      if (tokens >= 1.0d) {
        tokens -= 1.0d;
        return true;
      }
      return false;
    }

    private void refill(RateLimitPolicy policy, Instant now) {
      long nowMillis = now.toEpochMilli();
      long elapsed = nowMillis - lastTouchedMillis;
      if (elapsed > 0) {
        tokens = Math.min(policy.capacity(), tokens + elapsed * policy.refillPerMilli());
      }
      // Recorded even when the clock did not move, so an idle bucket is measured from its last
      // use rather than from its creation.
      lastTouchedMillis = Math.max(lastTouchedMillis, nowMillis);
    }
  }
}
