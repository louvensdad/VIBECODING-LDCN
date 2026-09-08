package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two collections of unchanged data are the same collection.
 *
 * <p>This is the property the whole engine rests on: a pack that cannot be rebuilt byte for byte
 * cannot be audited, because nobody can reproduce what was actually sent. The failure modes it
 * catches are quiet ones — a source dated at "now", a query with no total order, a set iterated in
 * hash order — none of which look wrong when read.
 */
class CollectorDeterminismTest extends CollectorTestSupport {

  @Test
  @DisplayName("Collecting twice from unchanged records yields identical items in identical order")
  void collectingTwiceIsIdentical() {
    Fixture fixture = createFullProject("Determinism");

    List<ContextItem> first = candidates.collect(fixture.projectId());
    List<ContextItem> second = candidates.collect(fixture.projectId());

    // ContextItem is a record, so this compares ids, kinds, labels, content and every field of
    // provenance — including recordedAt, which a "now" timestamp would break.
    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("Every source on its own is stable too, not just the sorted whole")
  void eachCollectorIsStableOnItsOwn() {
    Fixture fixture = createFullProject("DeterminismPerSource");

    for (ContextSourceType sourceType : ContextSourceType.values()) {
      List<ContextItem> first =
          candidates.collectFrom(sourceType, fixture.projectId(), ContextReadWindow.DEFAULT);
      List<ContextItem> second =
          candidates.collectFrom(sourceType, fixture.projectId(), ContextReadWindow.DEFAULT);
      assertThat(second).as("collector for %s", sourceType).isEqualTo(first);
    }
  }
}
