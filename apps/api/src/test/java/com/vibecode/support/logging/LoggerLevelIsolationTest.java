package com.vibecode.support.logging;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The ordering proof, in the direction that can actually fail: raise, then observe.
 *
 * <p>{@link LoggerLevelIsolationReverseOrderTest} runs the same two halves the other way round.
 * Both live in the same JVM and the same Logback {@link ch.qos.logback.classic.LoggerContext}, so
 * between them the four containers execute A, B, B, A or B, A, A, B depending on which class
 * Surefire reaches first — and every B asserts the untouched configuration either way. That
 * equality is the claim; a single direction would only show that a clean run stays clean.
 *
 * <p>Nested containers rather than two top-level classes, because the extension's class scope is
 * what has to be proven and a {@code @Nested} class is a container with its own {@code beforeAll}
 * and {@code afterAll}. It also makes the order a property of this file rather than of Surefire's
 * configuration, so the proof holds under any run order rather than one chosen to suit it.
 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class LoggerLevelIsolationTest {

  @Nested
  @Order(1)
  @ExtendWith(LoggerLevelIsolation.class)
  class RaiseFirst extends LoggingScenarios.RaisesLoggersToTrace {}

  @Nested
  @Order(2)
  @SpringBootTest(
      classes = LoggingScenarios.ApplicationConfigurationOnly.class,
      webEnvironment = SpringBootTest.WebEnvironment.NONE)
  class ObserveSecond extends LoggingScenarios.ExpectsTheDefaultConfiguration {}
}
