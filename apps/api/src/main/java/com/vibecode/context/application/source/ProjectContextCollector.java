package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.project.application.ProjectService;
import com.vibecode.project.domain.Project;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The project row itself: who it is and what it was for.
 *
 * <p>Each field becomes its own candidate rather than one paragraph stitched together. A single
 * merged item would force the selection step to take the idea and the status together or not at
 * all, and would make a shorter budget lose both. Splitting costs nothing here and leaves the
 * decision where it belongs.
 *
 * <p>The name and the original idea always exist — the entity refuses to be built without them. The
 * description may be absent, and an absent field yields no item: there is no record to represent, so
 * omitting it is not a filtering decision.
 */
@Component
@Transactional(readOnly = true)
public class ProjectContextCollector implements ContextCollector {

  private final ProjectService projects;

  public ProjectContextCollector(ProjectService projects) {
    this.projects = projects;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.PROJECT;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // requireReadable is the ownership gate: a project the caller does not own throws not-found
    // here, before a single field is read.
    Project project = projects.requireReadable(projectId);

    // The project row carries no revision, so the source is unversioned. Dated at updatedAt, which
    // is the last time this record actually changed.
    ContextSource source = ContextSource.of(ContextSourceType.PROJECT, project.getId().toString());
    ContextProvenance provenance =
        new ContextProvenance(source, project.getId(), project.getUpdatedAt());

    List<ContextItem> items = new ArrayList<>();
    items.add(
        new ContextItem(
            "project:" + project.getId() + ":name",
            ContextKind.NOTE,
            "Project name",
            project.getName(),
            provenance));

    if (project.getDescription() != null && !project.getDescription().isBlank()) {
      items.add(
          new ContextItem(
              "project:" + project.getId() + ":description",
              ContextKind.NOTE,
              "Project description",
              project.getDescription(),
              provenance));
    }

    items.add(
        new ContextItem(
            "project:" + project.getId() + ":idea",
            ContextKind.VISION,
            "Original idea",
            project.getOriginalIdea(),
            provenance));

    items.add(
        new ContextItem(
            "project:" + project.getId() + ":status",
            ContextKind.CURRENT_STATE,
            "Project status",
            "Status: "
                + project.getStatus()
                + "\nCurrent phase: "
                + (project.getCurrentPhase() == null ? "none" : project.getCurrentPhase()),
            provenance));

    // Built in a fixed sequence from a single row: the order is the code's, not the database's.
    return List.copyOf(items);
  }
}
