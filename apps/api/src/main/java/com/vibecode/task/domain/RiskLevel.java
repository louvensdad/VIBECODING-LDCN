package com.vibecode.task.domain;

/** How much damage a task can do if it goes wrong. Used to order and to warn, never to block. */
public enum RiskLevel {
  LOW,
  MEDIUM,
  HIGH,
  CRITICAL
}
