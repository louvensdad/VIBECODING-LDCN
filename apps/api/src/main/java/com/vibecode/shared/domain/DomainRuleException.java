package com.vibecode.shared.domain;

/**
 * Thrown when a request is well-formed but would break a domain rule — a duplicate position, a task
 * that does not belong to the project, a self-dependency. Mapped to HTTP 422.
 */
public class DomainRuleException extends RuntimeException {

  public DomainRuleException(String message) {
    super(message);
  }
}
