package com.vibecode.guardian.domain;

import com.vibecode.brain.domain.ProjectBrain;

/**
 * A guardian inspects official project state and reports risk. It never changes the project and
 * never blocks the user by itself — it produces findings the user decides on.
 *
 * <p>Contract only in this phase; no guardian is implemented yet.
 */
public interface Guardian {

  GuardianId id();

  GuardianReport inspect(ProjectBrain brain);
}
