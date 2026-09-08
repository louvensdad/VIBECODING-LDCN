package com.vibecode.context.application.source;

import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.project.application.ProjectService;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.Roadmap;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.roadmap.infrastructure.RoadmapRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The plan as a whole: the roadmap outline, and every phase in it.
 *
 * <p>Two shapes of candidate come out of this source and both are needed. The outline is the shape
 * of the plan — where the work sits relative to everything else — and no individual phase carries
 * it. Each phase's own description is detail the outline deliberately does not repeat.
 *
 * <p>Every phase is emitted, not only the unfinished ones. A completed phase is still part of the
 * plan, and deciding that a reader does not need it is a selection judgement this class is not
 * allowed to make. The phase the work is currently in is also collected separately under {@link
 * ContextSourceType#CURRENT_PHASE}; the two candidates carry different provenance and answer
 * different questions, and collapsing them would lose one of the two answers.
 *
 * <p>A project with no roadmap yields nothing. That is the absence of a record, not a filter.
 *
 * <p>This collector reads {@code RoadmapRepository} directly, by project id. That query would answer
 * for anyone, so what scopes it to the caller is the {@code projects.requireReadable} call at the
 * top of {@link #collect} — and only that call. The roadmap-bearing path happens to authorize again
 * further down inside {@code listPhases}, but the early return for a project with no roadmap reaches
 * no service at all, so deleting that one line would hand a stranger an empty list where a
 * not-found belongs. {@code CollectorOwnershipIsolationTest} keeps an unplanned project in its
 * fixture for exactly that reason.
 */
@Component
@Transactional(readOnly = true)
public class RoadmapContextCollector implements ContextCollector {

  private final ProjectService projects;
  private final RoadmapService roadmaps;
  private final RoadmapRepository roadmapRows;

  public RoadmapContextCollector(
      ProjectService projects, RoadmapService roadmaps, RoadmapRepository roadmapRows) {
    this.projects = projects;
    this.roadmaps = roadmaps;
    this.roadmapRows = roadmapRows;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.ROADMAP;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // The repository is queried by project id and answers for anyone, so the ownership check is
    // this method's own responsibility. RoadmapService.require would authorize but also throws
    // when a project simply has no roadmap yet, and "not planned yet" is not "not found".
    projects.requireReadable(projectId);
    Optional<Roadmap> roadmap = roadmapRows.findByProjectId(projectId);
    if (roadmap.isEmpty()) {
      return List.of();
    }

    // Already ordered by position, which is unique per roadmap — a total order that does not
    // depend on when rows were written.
    List<RoadmapPhase> phases = roadmaps.listPhases(projectId);
    List<ContextItem> items = new ArrayList<>();
    items.add(outlineOf(projectId, roadmap.get(), phases));
    phases.stream().map(phase -> detailOf(projectId, phase)).forEach(items::add);
    return List.copyOf(items);
  }

  private ContextItem outlineOf(UUID projectId, Roadmap roadmap, List<RoadmapPhase> phases) {
    String outline =
        phases.isEmpty()
            ? "The roadmap exists but has no phases yet."
            : phases.stream()
                .map(phase -> phase.getPosition() + ". " + phase.getTitle() + " [" + phase.getStatus() + "]")
                .collect(Collectors.joining("\n"));

    ContextSource source =
        ContextSource.of(ContextSourceType.ROADMAP, roadmap.getId().toString());
    return new ContextItem(
        "roadmap:" + roadmap.getId(),
        ContextKind.OBJECTIVE,
        "Roadmap",
        outline,
        new ContextProvenance(source, projectId, observedAt(roadmap, phases)));
  }

  /**
   * When the plan was last observed to change.
   *
   * <p><b>Not {@code roadmap.getUpdatedAt()}.</b> That column is written once, in the constructor,
   * and nothing ever writes it again — no {@code @PreUpdate}, no mutator, nothing in
   * {@code RoadmapService}. Dating the outline at it would have the item report the instant an empty
   * roadmap row was inserted, and go on reporting it after ten phases had been added, moved and
   * completed. Since the outline's content is built entirely from the phase rows, that is an item
   * whose text changes while its provenance swears it has not — and {@code ContextProvenance}
   * exists so that a stale item is visible as stale.
   *
   * <p>So the outline is dated at the newest phase, with the roadmap's own instant as the floor for
   * a plan that has no phases yet.
   */
  private Instant observedAt(Roadmap roadmap, List<RoadmapPhase> phases) {
    Instant latestPhase =
        phases.stream().map(RoadmapPhase::getUpdatedAt).max(Instant::compareTo).orElse(null);
    return SourceObservation.latestOf(roadmap.getUpdatedAt(), latestPhase);
  }

  /**
   * One phase, dated and identified by its own row rather than by the roadmap's. The source type
   * says which record produced the item — the plan — while the source id says exactly which phase,
   * so a reader can look it up and disagree with it.
   */
  private ContextItem detailOf(UUID projectId, RoadmapPhase phase) {
    StringBuilder content = new StringBuilder();
    content.append("Phase ").append(phase.getPosition()).append(": ").append(phase.getTitle());
    if (phase.getDescription() != null && !phase.getDescription().isBlank()) {
      content.append('\n').append(phase.getDescription());
    }
    content.append("\nStatus: ").append(phase.getStatus());

    ContextSource source = ContextSource.of(ContextSourceType.ROADMAP, phase.getId().toString());
    return new ContextItem(
        "roadmap-phase:" + phase.getId(),
        ContextKind.OBJECTIVE,
        phase.getTitle(),
        content.toString(),
        new ContextProvenance(source, projectId, phase.getUpdatedAt()));
  }
}
