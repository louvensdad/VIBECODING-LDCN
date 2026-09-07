package com.vibecode.task.infrastructure;

import com.vibecode.task.domain.TaskDependency;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskDependencyRepository
    extends JpaRepository<TaskDependency, TaskDependency.Key> {

  List<TaskDependency> findByTaskId(UUID taskId);

  boolean existsByTaskIdAndDependencyTaskId(UUID taskId, UUID dependencyTaskId);
}
