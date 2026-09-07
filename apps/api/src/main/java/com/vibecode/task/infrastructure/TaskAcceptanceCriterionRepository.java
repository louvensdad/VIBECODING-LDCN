package com.vibecode.task.infrastructure;

import com.vibecode.task.domain.TaskAcceptanceCriterion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskAcceptanceCriterionRepository
    extends JpaRepository<TaskAcceptanceCriterion, UUID> {

  List<TaskAcceptanceCriterion> findByTaskId(UUID taskId);
}
