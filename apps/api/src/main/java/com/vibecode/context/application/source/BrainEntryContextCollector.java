package com.vibecode.context.application.source;

import com.vibecode.brain.application.BrainService;
import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.context.domain.BrainEntryContextMapping;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every entry of official project memory, whatever it says.
 *
 * <p><b>All thirteen entry types survive.</b> There is no type this collector skips, and there must
 * never be one. A {@code NOTE} or a {@code PROMPT_RESULT} may well be the wrong thing to put in a
 * prompt, but that judgement belongs to the selection policy, which can be read and argued with. A
 * collector that dropped them would make the same judgement invisibly and permanently.
 *
 * <p><b>The meaning of an entry is not this class's to decide.</b> The kind comes from {@link
 * BrainEntryContextMapping#kindOf} and from nowhere else — no {@code switch}, no "close enough"
 * substitution. That mapping is exhaustive without a default branch, so a fourteenth entry type
 * stops the build until someone says what it means, and this collector inherits that guarantee for
 * free. Writing the mapping again here would break it: this copy would compile with a stale answer
 * while the real one refused to.
 *
 * <p>The source type is always {@link ContextSourceType#BRAIN_ENTRY}; the entry's own type becomes
 * the item's kind. Origin and meaning stay separate axes, so an item never claims to have come from
 * somewhere it did not.
 */
@Component
@Transactional(readOnly = true)
public class BrainEntryContextCollector implements ContextCollector {

  private final BrainService brain;

  public BrainEntryContextCollector(BrainService brain) {
    this.brain = brain;
  }

  @Override
  public ContextSourceType sourceType() {
    return ContextSourceType.BRAIN_ENTRY;
  }

  @Override
  public List<ContextItem> collect(UUID projectId, ContextReadWindow window) {
    // BrainService.list authorizes the project before querying; ownership is not re-implemented
    // here. The window is not applied: memory is the deliberate, curated record of the project and
    // is bounded by how much a user chose to write down, not by how fast a machine produces rows.
    return brain.list(projectId).stream()
        // Newest first, ties broken by id. The repository orders by createdAt alone, and entries
        // written in the same millisecond would otherwise come back in whatever order the database
        // felt like — enough to make two collections of unchanged data disagree.
        .sorted(
            Comparator.comparing(BrainEntry::getCreatedAt)
                .reversed()
                .thenComparing(entry -> entry.getId().toString()))
        .map(entry -> toCandidate(projectId, entry))
        .toList();
  }

  private ContextItem toCandidate(UUID projectId, BrainEntry entry) {
    ContextSource source =
        ContextSource.versioned(
            ContextSourceType.BRAIN_ENTRY, entry.getId().toString(), entry.getVersion());
    return new ContextItem(
        "brain:" + entry.getId(),
        BrainEntryContextMapping.kindOf(entry.getType()),
        entry.getTitle(),
        entry.getContent(),
        new ContextProvenance(source, projectId, entry.getCreatedAt()));
  }
}
