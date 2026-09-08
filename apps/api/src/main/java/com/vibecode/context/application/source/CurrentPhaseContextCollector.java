package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.state.application.ProjectStateService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The phase the work is currently in.
 *
 * <p>Which phase that is is not decided here. {@link ProjectStateService} already answers "where is
 * this project?" for the whole application, and a second opinion computed inside the context engine
 * would drift from the one the user sees on their own dashboard.
 *
 * <p>A project with no plan, or one whose phases are all finished, yields nothing.
 */
@Component
@Transactional(readOnly = true)
public class CurrentPhaseContextCollector implements ContextCollector {

  private final ProjectStateService state;

  public CurrentPhaseContextCollector(ProjectStateService state) {
    this.state = state;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.CURRENT_PHASE;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // ProjectStateService.of authorizes the project before computing anything.
    RoadmapPhase phase = state.of(projectId).currentPhase();
    if (phase == null) {
      return List.of();
    }

    StringBuilder content = new StringBuilder();
    content.append("Phase ").append(phase.getPosition()).append(": ").append(phase.getTitle());
    if (phase.getDescription() != null && !phase.getDescription().isBlank()) {
      content.append('\n').append(phase.getDescription());
    }
    content.append("\nStatus: ").append(phase.getStatus());

    ContextSource source =
        ContextSource.of(ContextSourceType.CURRENT_PHASE, phase.getId().toString());
    return List.of(
        new ContextItem(
            "current-phase:" + phase.getId(),
            ContextKind.OBJECTIVE,
            phase.getTitle(),
            content.toString(),
            new ContextProvenance(source, projectId, phase.getUpdatedAt())));
  }
}
