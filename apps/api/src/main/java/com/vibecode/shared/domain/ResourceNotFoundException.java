package com.vibecode.shared.domain;

/** Thrown when a requested aggregate does not exist. Mapped to HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

  public ResourceNotFoundException(String message) {
    super(message);
  }
}
