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
 * <p><b>On the kinds, because one of them was wrong.</b> The identity item and the original idea are
 * both {@link ContextKind#VISION}: that constant means the product vision — what is being built —
 * and the name-plus-description is the shortest statement the system holds of exactly that, with the
 * original idea the longest. The status item is {@code CURRENT_STATE}, which as a kind is what a
 * record <em>says</em> about where things stand, whatever source it was read from.
 *
 * <p>These were previously filed as {@code NOTE}, which was a mistake worth naming. {@code NOTE}'s
 * own javadoc says it is "not a fallback: a note is chosen, never assigned because nothing else
 * fitted", and that is precisely how it was being used — the fallback the enum exists to forbid.
 *
 * <p><b>A vocabulary gap, for the record.</b> None of the seventeen kinds means "the identity of the
 * thing being built". A bare project name is not a vision, a note or a decision; it is the handle
 * everything else is addressed by. This collector does not paper over that with a nearest-fit
 * constant: it emits the name joined to the description, where {@code VISION} is an honest fit for
 * the pair, and the name is never filed as a kind on its own. If an {@code IDENTITY} kind is ever
 * wanted so the inspector can tell a name from a vision statement, that is a deliberate change to
 * {@code ContextKind} and belongs to whoever owns the domain vocabulary, not here.
 *
 * <p>The name and the original idea always exist — the entity refuses to be built without them. The
 * description may be absent, and an absent field adds nothing to the identity item: there is no
 * record to represent, so omitting it is not a filtering decision.
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
    // is a sentence about an unnamed thing. Emitted together they say what is being built, which is
    // what VISION means. Both strings appear verbatim, so nothing is lost by joining them.
    String identity =
        project.getDescription() == null || project.getDescription().isBlank()
            ? project.getName()
            : project.getName() + "\n" + project.getDescription();

    List<ContextItem> items = new ArrayList<>();
    items.add(
        new ContextItem(
            "project:" + project.getId() + ":identity",
            ContextKind.VISION,
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
