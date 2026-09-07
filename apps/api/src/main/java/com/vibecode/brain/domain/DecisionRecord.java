package com.vibecode.brain.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A decision, with the reasoning that produced it.
 *
 * <p>Decisions are persisted as {@link BrainEntryType#DECISION} entries; this record is the shape
 * the guide and prompt modules read them back as, so a future model handoff can restate not only
 * what was decided but why.
 */
public record DecisionRecord(
    UUID brainEntryId,
    UUID projectId,
    String title,
    String decision,
    String rationale,
    String source,
    Instant decidedAt) {}
