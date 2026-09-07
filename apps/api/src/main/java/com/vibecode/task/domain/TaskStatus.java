package com.vibecode.task.domain;

/** Lifecycle of a task. A task only reaches DONE with evidence behind it. */
public enum TaskStatus {
  TODO,
  IN_PROGRESS,
  BLOCKED,
  DONE
}
