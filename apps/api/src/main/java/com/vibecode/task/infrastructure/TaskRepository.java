package com.vibecode.task.infrastructure;

import com.vibecode.task.domain.Task;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, UUID> {

  List<Task> findByProjectId(UUID projectId);

  List<Task> findByPhaseIdOrderByPosition(UUID phaseId);

  boolean existsByPhaseIdAndPosition(UUID phaseId, int position);
}
