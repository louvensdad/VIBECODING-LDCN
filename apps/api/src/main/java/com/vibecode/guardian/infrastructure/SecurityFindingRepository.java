package com.vibecode.guardian.infrastructure;

import com.vibecode.guardian.domain.SecurityFinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityFindingRepository extends JpaRepository<SecurityFinding, UUID> {

  List<SecurityFinding> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

  Optional<SecurityFinding> findByProjectIdAndFingerprint(UUID projectId, String fingerprint);

  Optional<SecurityFinding> findByIdAndProjectId(UUID id, UUID projectId);
}

