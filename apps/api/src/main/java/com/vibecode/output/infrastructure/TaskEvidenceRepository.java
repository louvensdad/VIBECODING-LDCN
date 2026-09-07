package com.vibecode.output.infrastructure;

import com.vibecode.output.domain.TaskEvidence;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskEvidenceRepository extends JpaRepository<TaskEvidence, UUID> {

  List<TaskEvidence> findByTaskIdOrderByCreatedAtDescIdDesc(UUID taskId);

  List<TaskEvidence> findByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId);

  Optional<TaskEvidence> findFirstByTaskIdOrderByCreatedAtDescIdDesc(UUID taskId);
}
