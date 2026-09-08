package com.vibecode.context.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;

class EstimatedTokenCountTest {

  @Test
  void everyRouteToTheNumberIsNamedAsAnEstimate() {
    // The point of the type: there is no accessor a reader could copy into a place that means
    // "exact". Any numeric getter added later without "estimated" in its name fails here.
    for (Method method : EstimatedTokenCount.class.getDeclaredMethods()) {
      if (!method.getReturnType().isPrimitive() || method.getReturnType() == boolean.class) {
        continue;
      }
      if (method.isSynthetic() || !java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      if (method.getName().equals("hashCode")) {
        continue; // Inherited contract, not a way to read the number.
      }
      assertThat(method.getName().toLowerCase())
          .as("numeric accessor %s must say it is an estimate", method.getName())
          .contains("estimated");
    }
  }

  @Test
  void theEstimateAdmitsItIsNotExactAndNamesItsHeuristic() {
    EstimatedTokenCount estimate = EstimatedTokenCount.fromCharacters(10);

    assertThat(estimate.isExact()).isFalse();
    assertThat(estimate.heuristic()).contains("no provider tokenizer");
    assertThat(estimate.toString()).contains("estimate").contains("~");
  }

  @Test
  void anEstimateCannotBecomeABudgetLimit() {
    // ContextBudget must stay purely countable: no token dimension may appear on it, or an
    // approximate number would end up enforced as if it were a measurement.
    for (RecordComponent component : ContextBudget.class.getRecordComponents()) {
      assertThat(component.getName().toLowerCase()).doesNotContain("token");
    }
    for (Method method : ContextBudget.class.getDeclaredMethods()) {
      assertThat(method.getName().toLowerCase()).doesNotContain("token");
    }
    assertThat(ContextBudget.class.getDeclaredFields())
        .noneMatch(field -> field.getType() == EstimatedTokenCount.class);
  }

  @Test
  void usageHandsBackAnEstimateOnlyThroughTheEstimateType() {
    ContextUsage usage = new ContextUsage(2, 9L, 9L);

    // The exact numbers are plain longs; the guess is not, and cannot be assigned to one.
    assertThat(usage.characters()).isEqualTo(9L);
    EstimatedTokenCount estimate = usage.estimatedTokens();
    assertThat(estimate.estimatedTokens()).isEqualTo(3L);
    assertThat(EstimatedTokenCount.class.isAssignableFrom(Long.class)).isFalse();
  }

  @Test
  void anEstimateWithoutAStatedHeuristicIsRefused() {
    assertThatThrownBy(() -> new EstimatedTokenCount(5L, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("heuristic");
    assertThatThrownBy(() -> new EstimatedTokenCount(-1L, "anything"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
