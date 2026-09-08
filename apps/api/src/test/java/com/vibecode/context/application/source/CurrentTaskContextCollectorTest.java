package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextSourceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CurrentTaskContextCollectorTest extends CollectorTestSupport {

  @Test
  @DisplayName("The current task yields its objective and its standing, separately")
  void currentTaskIsCollected() {
    Fixture fixture = createFullProject("TaskCollector");

    List<ContextItem> items =
        candidates.collectFrom(
            ContextSourceType.CURRENT_TASK, fixture.projectId(), ContextReadWindow.DEFAULT);

    assertThat(items)
        .extracting(ContextItem::id)
        .containsExactly(
            "current-task:" + fixture.blockedTask().getId() + ":objective",
            "current-task:" + fixture.blockedTask().getId() + ":status");

    ContextItem objective = items.get(0);
    assertThat(objective.kind()).isEqualTo(ContextKind.OBJECTIVE);
    assertThat(objective.label()).isEqualTo(fixture.blockedTask().getTitle());
    assertThat(objective.content()).isEqualTo(fixture.blockedTask().getObjective());
    assertThat(objective.provenance().sourceType()).isEqualTo(ContextSourceType.CURRENT_TASK);
    assertThat(objective.provenance().sourceId())
        .isEqualTo(fixture.blockedTask().getId().toString());

    assertThat(items.get(1).kind()).isEqualTo(ContextKind.CURRENT_STATE);
    assertThat(items.get(1).content()).contains("Status: BLOCKED");
  }
}
