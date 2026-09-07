package com.vibecode.brain.infrastructure;

import com.vibecode.brain.domain.MemoryProposalStatus;
import com.vibecode.brain.domain.MemoryUpdateProposal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryUpdateProposalRepository extends JpaRepository<MemoryUpdateProposal, UUID> {

  List<MemoryUpdateProposal> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

  List<MemoryUpdateProposal> findByProjectIdAndStatusOrderByCreatedAtDesc(
      UUID projectId, MemoryProposalStatus status);
}
