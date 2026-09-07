package com.vibecode.project.application;

import com.vibecode.identity.domain.CurrentUser;
import com.vibecode.project.domain.Project;
import org.springframework.stereotype.Component;

/**
 * The single answer to "may this user touch this project?".
 *
 * <p>It exists so the question is asked in one place instead of being re-implemented as an
 * {@code if} in every controller — where one forgotten check is a data breach.
 *
 * <p>Today the rule is small on purpose: the owner may do everything, and nobody else may do
 * anything. In particular an ADMIN gets no access to other people's projects. Granting it would be
 * a real decision about who can read a stranger's work, and this phase has no requirement for it —
 * least privilege wins until something concrete argues otherwise.
 *
 * <p>The three levels are separate so a future {@code ProjectMembership} can grant read without
 * write, or write without the ability to delete, without reshaping the callers.
 */
@Component
public class ProjectAccessPolicy {

  /** May see the project and everything hanging off it. */
  public boolean canRead(CurrentUser user, Project project) {
    return isOwner(user, project);
  }

  /** May add and change project content: roadmap, tasks, evidence, memory. */
  public boolean canWrite(CurrentUser user, Project project) {
    return isOwner(user, project);
  }

  /** May change the project itself: settings, ownership, deletion. */
  public boolean canManage(CurrentUser user, Project project) {
    return isOwner(user, project);
  }

  /**
   * A project with no owner — a row predating identity — matches nobody. It is retained in the
   * database and reachable by no one until someone claims it deliberately.
   */
  private boolean isOwner(CurrentUser user, Project project) {
    return user != null
        && project != null
        && project.getOwnerUserId() != null
        && project.getOwnerUserId().equals(user.id());
  }
}
