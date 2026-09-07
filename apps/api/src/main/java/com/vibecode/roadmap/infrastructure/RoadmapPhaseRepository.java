package com.vibecode.roadmap.infrastructure;

import com.vibecode.roadmap.domain.RoadmapPhase;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoadmapPhaseRepository extends JpaRepository<RoadmapPhase, UUID> {

  List<RoadmapPhase> findByRoadmapIdOrderByPosition(UUID roadmapId);

  boolean existsByRoadmapIdAndPosition(UUID roadmapId, int position);
}
