package com.vibecode.support.logging;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The other direction of {@link LoggerLevelIsolationTest}: observe, then raise.
 *
 * <p>On its own this direction is weak — nothing has raised anything yet when B runs, so B would
 * pass with no isolation at all. It earns its place because the two classes share a JVM: whichever
 * of them Surefire runs second, its observing half is preceded by a raising half, so neither class
 * can be the one that happens to be scheduled somewhere harmless.
 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class LoggerLevelIsolationReverseOrderTest {

  @Nested
  @Order(1)
  @SpringBootTest(
      classes = LoggingScenarios.ApplicationConfigurationOnly.class,
      webEnvironment = SpringBootTest.WebEnvironment.NONE)
  class ObserveFirst extends LoggingScenarios.ExpectsTheDefaultConfiguration {}

  @Nested
  @Order(2)
  @ExtendWith(LoggerLevelIsolation.class)
  class RaiseSecond extends LoggingScenarios.RaisesLoggersToTrace {}
}
