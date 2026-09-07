package com.vibecode.task.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** "This task cannot start until that one is finished." */
@Entity
@Table(name = "task_dependencies")
@IdClass(TaskDependency.Key.class)
public class TaskDependency {

  @Id
  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Id
  @Column(name = "dependency_task_id", nullable = false)
  private UUID dependencyTaskId;

  /** For JPA only. */
  protected TaskDependency() {}

  public TaskDependency(UUID taskId, UUID dependencyTaskId) {
    this.taskId = taskId;
    this.dependencyTaskId = dependencyTaskId;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public UUID getDependencyTaskId() {
    return dependencyTaskId;
  }

  /** Composite key: a dependency is identified by the pair, not by a surrogate id. */
  public static class Key implements Serializable {

    private UUID taskId;
    private UUID dependencyTaskId;

    public Key() {}

    public Key(UUID taskId, UUID dependencyTaskId) {
      this.taskId = taskId;
      this.dependencyTaskId = dependencyTaskId;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      return other instanceof Key key
          && Objects.equals(taskId, key.taskId)
          && Objects.equals(dependencyTaskId, key.dependencyTaskId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(taskId, dependencyTaskId);
    }
  }
}
