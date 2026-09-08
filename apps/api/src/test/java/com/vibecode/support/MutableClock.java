package com.vibecode.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock tests can move.
 *
 * <p>Window behaviour has to be provable without waiting fifteen real minutes, and a test that
 * sleeps is a test that is slow and flaky at the same time. This starts at the real time so
 * anything else reading the clock behaves normally, and only moves when a test asks.
 */
public class MutableClock extends Clock {

  private final ZoneId zone;
  private final AtomicReference<Instant> now;

  public MutableClock() {
    this(ZoneId.of("UTC"), Instant.now());
  }

  private MutableClock(ZoneId zone, Instant start) {
    this.zone = zone;
    this.now = new AtomicReference<>(start);
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId otherZone) {
    return new MutableClock(otherZone, now.get());
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  public void advance(Duration amount) {
    now.updateAndGet(current -> current.plus(amount));
  }

  /** Back to real time, so one test's time travel cannot affect the next. */
  public void reset() {
    now.set(Instant.now());
  }
}
