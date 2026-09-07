package com.vibecode.guide.domain;

import java.util.UUID;

/**
 * The Project Guide answers the five questions a user asks when they come back to a project after a
 * week away.
 *
 * <p>It reads official state only. No model call sits behind this interface, and when one is added
 * later it will feed a proposal, not this answer. Contract only in this phase.
 */
public interface ProjectGuide {

  GuidanceReport describeSituation(UUID projectId);
}
