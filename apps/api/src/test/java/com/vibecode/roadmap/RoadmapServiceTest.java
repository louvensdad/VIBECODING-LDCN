package com.vibecode.roadmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.project.application.ProjectService;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.PhaseStatus;
import com.vibecode.roadmap.domain.Roadmap;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.shared.domain.DomainRuleException;
import com.vibecode.shared.domain.ResourceNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.vibecode.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RoadmapServiceTest {

  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TestIdentity identity;

  @BeforeEach
  void authenticate() {
    identity.createAndAuthenticate("Owner");
  }

  @AfterEach
  void signOut() {
    identity.clear();
  }


  private UUID newProject(String name) {
    return projects.create(name, "", "Ideia de " + name).getId();
  }

  @Test
  @DisplayName("a project has exactly one roadmap")
  void oneRoadmapPerProject() {
    UUID projectId = newProject("Único");

    Roadmap first = roadmaps.createOrGet(projectId);
    Roadmap second = roadmaps.createOrGet(projectId);

    assertThat(second.getId()).isEqualTo(first.getId());
  }

  @Test
  @DisplayName("phases keep the order they were given")
  void phasesAreOrdered() {
    UUID projectId = newProject("Ordem");
    roadmaps.createOrGet(projectId);
    roadmaps.addPhase(projectId, 2, "Authentication", null);
    roadmaps.addPhase(projectId, 1, "Setup", null);
    roadmaps.addPhase(projectId, 3, "Dashboard", null);

    assertThat(roadmaps.listPhases(projectId))
        .extracting(RoadmapPhase::getTitle)
        .containsExactly("Setup", "Authentication", "Dashboard");
  }

  @Test
  @DisplayName("a duplicated or invalid position is rejected")
  void positionsAreValidated() {
    UUID projectId = newProject("Posições");
    roadmaps.addPhase(projectId, 1, "Setup", null);

    assertThatThrownBy(() -> roadmaps.addPhase(projectId, 1, "Outra", null))
        .isInstanceOf(DomainRuleException.class)
        .hasMessageContaining("already taken");

    assertThatThrownBy(() -> roadmaps.addPhase(projectId, 0, "Zero", null))
        .isInstanceOf(DomainRuleException.class);
  }

  @Test
  @DisplayName("reordering leaves a contiguous sequence with no gaps or duplicates")
  void reorderingKeepsPositionsContiguous() {
    UUID projectId = newProject("Reordenar");
    roadmaps.addPhase(projectId, 1, "Setup", null);
    RoadmapPhase dashboard = roadmaps.addPhase(projectId, 2, "Dashboard", null);
    roadmaps.addPhase(projectId, 3, "Deploy", null);

    roadmaps.movePhase(projectId, dashboard.getId(), 3);

    assertThat(roadmaps.listPhases(projectId))
        .extracting(RoadmapPhase::getTitle)
        .containsExactly("Setup", "Deploy", "Dashboard");
    assertThat(roadmaps.listPhases(projectId))
        .extracting(RoadmapPhase::getPosition)
        .containsExactly(1, 2, 3);

    assertThatThrownBy(() -> roadmaps.movePhase(projectId, dashboard.getId(), 9))
        .isInstanceOf(DomainRuleException.class);
  }

  @Test
  @DisplayName("an empty phase is planned, not complete")
  void emptyPhaseIsPlanned() {
    UUID projectId = newProject("Vazia");
    RoadmapPhase phase = roadmaps.addPhase(projectId, 1, "Vazia", null);

    assertThat(phase.getStatus()).isEqualTo(PhaseStatus.PLANNED);
  }

  @Test
  @DisplayName("a project without a roadmap reports it plainly")
  void missingRoadmapIsNotFound() {
    UUID projectId = newProject("Sem roadmap");

    assertThatThrownBy(() -> roadmaps.require(projectId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("no roadmap");
    assertThat(roadmaps.listPhases(projectId)).isEmpty();
  }

  @Test
  @DisplayName("a phase from another project is refused")
  void phasesBelongToTheirProject() {
    UUID one = newProject("Projeto 1");
    UUID two = newProject("Projeto 2");
    RoadmapPhase phase = roadmaps.addPhase(one, 1, "Fase", null);
    roadmaps.addPhase(two, 1, "Outra", null);

    assertThatThrownBy(() -> roadmaps.requirePhase(two, phase.getId()))
        .isInstanceOf(DomainRuleException.class)
        .hasMessageContaining("another project");
  }
}
