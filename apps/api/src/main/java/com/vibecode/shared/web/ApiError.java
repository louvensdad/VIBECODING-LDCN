package com.vibecode.shared.web;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape returned by every VibeCode endpoint.
 *
 * <p>Messages are written for the user and must never carry secrets, credentials or raw stack
 * traces.
 */
public record ApiError(
    Instant timestamp, int status, String code, String message, List<FieldViolation> violations) {

  public record FieldViolation(String field, String message) {}

  public static ApiError of(int status, String code, String message) {
    return new ApiError(Instant.now(), status, code, message, List.of());
  }

  public static ApiError of(int status, String code, String message, List<FieldViolation> fields) {
    return new ApiError(Instant.now(), status, code, message, fields);
  }
}
