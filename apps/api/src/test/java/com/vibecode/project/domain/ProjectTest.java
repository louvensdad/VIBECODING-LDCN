package com.vibecode.project.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectTest {

  @Test
  void startsActiveInTheFoundationPhase() {
    Project project = new Project("Atlas", "A reliable product", "Track my build with memory");

    assertThat(project.getId()).isNotNull();
    assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    assertThat(project.getCurrentPhase()).isEqualTo(Project.INITIAL_PHASE);
    assertThat(project.getCreatedAt()).isEqualTo(project.getUpdatedAt());
  }

  @Test
  void movingPhaseUpdatesTheTimestamp() throws InterruptedException {
    Project project = new Project("Atlas", null, "An idea");
    Thread.sleep(2);

    project.moveToPhase("Authentication");

    assertThat(project.getCurrentPhase()).isEqualTo("Authentication");
    assertThat(project.getUpdatedAt()).isAfter(project.getCreatedAt());
  }

  @Test
  void statusChangesAreExplicit() {
    Project project = new Project("Atlas", null, "An idea");

    project.changeStatus(ProjectStatus.PAUSED);

    assertThat(project.getStatus()).isEqualTo(ProjectStatus.PAUSED);
  }
}
