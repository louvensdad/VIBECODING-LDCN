package com.vibecode.roadmap.infrastructure;

import com.vibecode.roadmap.domain.Roadmap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoadmapRepository extends JpaRepository<Roadmap, UUID> {

  Optional<Roadmap> findByProjectId(UUID projectId);
}
