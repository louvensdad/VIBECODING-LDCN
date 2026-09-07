package com.vibecode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * VibeCode API — a modular monolith.
 *
 * <p>Modules live under {@code com.vibecode.<module>} and are wired by component scanning from this
 * package. See {@code docs/architecture/ARCHITECTURE.md} for what each module owns.
 */
@SpringBootApplication
public class VibeCodeApplication {

  public static void main(String[] args) {
    SpringApplication.run(VibeCodeApplication.class, args);
  }
}
