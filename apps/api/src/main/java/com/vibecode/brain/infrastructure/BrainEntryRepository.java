package com.vibecode.brain.infrastructure;

import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.brain.domain.BrainEntryType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrainEntryRepository extends JpaRepository<BrainEntry, UUID> {

  List<BrainEntry> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

  List<BrainEntry> findByProjectIdAndTypeOrderByCreatedAtDesc(UUID projectId, BrainEntryType type);
}
