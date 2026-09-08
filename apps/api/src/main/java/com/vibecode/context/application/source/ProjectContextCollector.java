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
 * <p>Three candidates, split by what they are rather than by which column they live in. Folding them
 * into one paragraph would force the selection step to take the idea and the status together or not
 * at all, and a shorter budget would lose both.
 *
 * <p><b>On the kinds.</b> The identity item is {@link ContextKind#PROJECT_IDENTITY}: the name and
 * description are the handle the project is addressed by, and that constant was added to the
 * vocabulary to say so. The original idea stays {@link ContextKind#VISION} — that constant means the
 * product vision, what is being built, and the idea the project was created from is exactly that.
 * The status item is {@code CURRENT_STATE}, which as a kind is what a record <em>says</em> about
 * where things stand, whatever source it was read from.
 *
 * <p>Two earlier filings were wrong and are worth naming so they are not repeated. These fields were
 * once {@code NOTE}, which {@code NOTE}'s own javadoc forbids — "a note is chosen, never assigned
 * because nothing else fitted" — and that is precisely how it was being used. They were then moved
 * to {@code VISION}, an honest fit for a name-plus-description read as the shortest vision
 * statement, but still a nearest fit made because the vocabulary had no word for identity. It has
 * one now.
 *
 * <p>The name and the original idea always exist — the entity refuses to be built without them. The
 * description may be absent, and an absent field is an absent record, not content: the identity item
 * is then the name alone. Nothing is written to stand in for it — no template sentence, no "no
 * description", nothing a later reader could mistake for something the project actually said.
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

    // The name and the description are one statement the schema happens to keep in two columns:
    // "VibeCode" answers nothing on its own, and a description read without the name it belongs to
    // is a sentence about an unnamed thing. Joined by a newline in a fixed order, so the same row
    // always renders the same string; both appear verbatim, so joining them loses nothing. When
    // there is no description the item is the name and stops there.
    String identity =
        project.getDescription() == null || project.getDescription().isBlank()
            ? project.getName()
            : project.getName() + "\n" + project.getDescription();

    List<ContextItem> items = new ArrayList<>();
    items.add(
        new ContextItem(
            "project:" + project.getId() + ":identity",
            ContextKind.PROJECT_IDENTITY,
            "Project",
            identity,
            provenance));

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
