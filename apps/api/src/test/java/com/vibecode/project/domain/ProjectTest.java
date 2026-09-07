package com.vibecode.project.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectTest {

  private static final UUID OWNER = UUID.randomUUID();

  @Test
  void startsActiveInTheFoundationPhase() {
    Project project =
        new Project(OWNER, "Atlas", "A reliable product", "Track my build with memory");

    assertThat(project.getId()).isNotNull();
    assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    assertThat(project.getCurrentPhase()).isEqualTo(Project.INITIAL_PHASE);
    assertThat(project.getCreatedAt()).isEqualTo(project.getUpdatedAt());
    assertThat(project.getOwnerUserId()).isEqualTo(OWNER);
  }

  @Test
  void movingPhaseUpdatesTheTimestamp() throws InterruptedException {
    Project project = new Project(OWNER, "Atlas", null, "An idea");
    Thread.sleep(2);

    project.moveToPhase("Authentication");

    assertThat(project.getCurrentPhase()).isEqualTo("Authentication");
    assertThat(project.getUpdatedAt()).isAfter(project.getCreatedAt());
  }

  @Test
  void aProjectCannotExistWithoutAnOwner() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new Project(null, "Atlas", null, "An idea"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("owner");
  }

  @Test
  void statusChangesAreExplicit() {
    Project project = new Project(OWNER, "Atlas", null, "An idea");

    project.changeStatus(ProjectStatus.PAUSED);

    assertThat(project.getStatus()).isEqualTo(ProjectStatus.PAUSED);
  }
}
