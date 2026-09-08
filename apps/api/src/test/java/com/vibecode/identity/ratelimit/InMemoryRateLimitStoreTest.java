package com.vibecode.identity.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.identity.ratelimit.domain.RateLimitKey;
import com.vibecode.identity.ratelimit.domain.RateLimitPolicy;
import com.vibecode.identity.ratelimit.domain.RateLimitScope;
import com.vibecode.identity.ratelimit.infrastructure.InMemoryRateLimitStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The store on its own: no Spring, no clock of its own, so window behaviour is exercised by moving
 * an {@link Instant} rather than by sleeping.
 */
class InMemoryRateLimitStoreTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private RateLimitPolicy policy(int capacity, Duration window) {
    return new RateLimitPolicy(RateLimitScope.LOGIN_ACCOUNT, "test", capacity, window);
  }

  private RateLimitKey key(String name) {
    return new RateLimitKey(RateLimitScope.LOGIN_ACCOUNT, name);
  }

  @Test
  @DisplayName("a bucket allows exactly its capacity, then refuses")
  void capacityIsRespected() {
    InMemoryRateLimitStore store = new InMemoryRateLimitStore(100);
    RateLimitPolicy policy = policy(5, Duration.ofMinutes(15));

    for (int attempt = 1; attempt <= 5; attempt++) {
      assertThat(store.tryConsume(key("a"), policy, T0))
          .as("tentativa %d deveria passar", attempt)
          .isTrue();
    }
    assertThat(store.tryConsume(key("a"), policy, T0)).isFalse();
  }

  @Test
  @DisplayName("buckets are independent of one another")
  void bucketsDoNotShareTokens() {
    InMemoryRateLimitStore store = new InMemoryRateLimitStore(100);
    RateLimitPolicy policy = policy(2, Duration.ofMinutes(15));

    assertThat(store.tryConsume(key("a"), policy, T0)).isTrue();
    assertThat(store.tryConsume(key("a"), policy, T0)).isTrue();
    assertThat(store.tryConsume(key("a"), policy, T0)).isFalse();

    assertThat(store.tryConsume(key("b"), policy, T0)).isTrue();
  }

  @Test
  @DisplayName("tokens come back as time passes, without sleeping for the window")
  void bucketRefillsOverTime() {
    InMemoryRateLimitStore store = new InMemoryRateLimitStore(100);
    RateLimitPolicy policy = policy(3, Duration.ofMinutes(15));

    for (int i = 0; i < 3; i++) {
      assertThat(store.tryConsume(key("a"), policy, T0)).isTrue();
    }
    assertThat(store.tryConsume(key("a"), policy, T0)).isFalse();

    // A third of the window restores roughly one token.
    assertThat(store.tryConsume(key("a"), policy, T0.plus(Duration.ofMinutes(5)))).isTrue();
    assertThat(store.tryConsume(key("a"), policy, T0.plus(Duration.ofMinutes(5)))).isFalse();

    // A full window past the drain restores the whole capacity.
    Instant later = T0.plus(Duration.ofMinutes(30));
    for (int i = 0; i < 3; i++) {
      assertThat(store.tryConsume(key("a"), policy, later)).isTrue();
    }
  }

  @Test
  @DisplayName("reset puts a drained bucket back to full")
  void resetRefillsTheBucket() {
    InMemoryRateLimitStore store = new InMemoryRateLimitStore(100);
    RateLimitPolicy policy = policy(2, Duration.ofMinutes(15));

    store.tryConsume(key("a"), policy, T0);
    store.tryConsume(key("a"), policy, T0);
    assertThat(store.tryConsume(key("a"), policy, T0)).isFalse();

    store.reset(key("a"));

    assertThat(store.tryConsume(key("a"), policy, T0)).isTrue();
  }

  @Test
  @DisplayName("concurrent callers cannot together exceed the capacity")
  void concurrentConsumersRespectCapacity() throws Exception {
    InMemoryRateLimitStore store = new InMemoryRateLimitStore(100);
    RateLimitPolicy policy = policy(5, Duration.ofMinutes(15));

    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch startTogether = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(threads);
    AtomicInteger allowed = new AtomicInteger();

    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            try {
              startTogether.await();
              // A frozen instant: any "allowed" beyond capacity would be a lost update, not refill.
              if (store.tryConsume(key("shared"), policy, T0)) {
                allowed.incrementAndGet();
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              finished.countDown();
            }
          });
    }

    startTogether.countDown();
    assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
    pool.shutdownNow();

    assertThat(allowed.get()).as("exatamente a capacidade, nem mais nem menos").isEqualTo(5);
  }

  @Test
  @DisplayName("inventing identifiers cannot grow the store without bound")
  void cardinalityIsBounded() {
    int maxEntries = 500;
    InMemoryRateLimitStore store = new InMemoryRateLimitStore(maxEntries);
    RateLimitPolicy policy = policy(5, Duration.ofMinutes(15));

    // The shape of a real attack: a fresh, never-seen identifier every time.
    for (int i = 0; i < 50_000; i++) {
      store.tryConsume(key(UUID.randomUUID().toString()), policy, T0);
    }

    assertThat(store.size())
        .as("a estrutura não pode crescer com o número de identificadores inventados")
        .isLessThanOrEqualTo(maxEntries);
  }

  @Test
  @DisplayName("idle buckets are dropped, so memory is reclaimed rather than just capped")
  void idleBucketsAreEvicted() {
    int maxEntries = 100;
    InMemoryRateLimitStore store = new InMemoryRateLimitStore(maxEntries);
    RateLimitPolicy policy = policy(5, Duration.ofMinutes(15));

    for (int i = 0; i < 80; i++) {
      store.tryConsume(key("old-" + i), policy, T0);
    }
    assertThat(store.size()).isGreaterThan(50);

    // Well past the retention period, a single new attempt sweeps the stale ones away.
    Instant muchLater = T0.plus(Duration.ofHours(2));
    store.tryConsume(key("fresh"), policy, muchLater);

    assertThat(store.size()).as("entradas ociosas deveriam ter sido descartadas").isLessThan(5);
  }

  @Test
  @DisplayName("an evicted bucket comes back full, which is the safe direction to be wrong in")
  void evictionFailsOpenNotClosed() {
    InMemoryRateLimitStore store = new InMemoryRateLimitStore(2);
    RateLimitPolicy policy = policy(1, Duration.ofMinutes(15));

    assertThat(store.tryConsume(key("victim"), policy, T0)).isTrue();
    assertThat(store.tryConsume(key("victim"), policy, T0)).isFalse();

    // Push it out with other traffic.
    store.tryConsume(key("x"), policy, T0);
    store.tryConsume(key("y"), policy, T0);
    store.tryConsume(key("z"), policy, T0);

    // It is allowed again — the cost of a bounded store, and the reason the bound is large.
    assertThat(store.tryConsume(key("victim"), policy, T0)).isTrue();
  }
}
