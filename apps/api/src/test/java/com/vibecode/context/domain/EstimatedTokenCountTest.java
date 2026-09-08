package com.vibecode.context.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;

class EstimatedTokenCountTest {

  @Test
  void everyRouteToTheNumberIsNamedAsAnEstimate() {
    // The point of the type: there is no accessor a reader could copy into a place that means
    // "exact". Boxed returns count too — a later `public Long tokens()` must fail this.
    for (Method method : EstimatedTokenCount.class.getDeclaredMethods()) {
      if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      if (method.getName().equals("hashCode")) {
        continue; // Inherited contract, not a way to read the number.
      }
      Class<?> returned = method.getReturnType();
      boolean numeric =
          (returned.isPrimitive() && returned != boolean.class && returned != void.class)
              || Number.class.isAssignableFrom(returned);
      if (!numeric) {
        continue;
      }
      assertThat(method.getName().toLowerCase())
          .as("numeric accessor %s must say it is an estimate", method.getName())
          .contains("estimated");
    }
  }

  @Test
  void anEstimateCannotBeConstructedWithAHeuristicTheCallerInvents() {
    // usage.domain.UsageEvent holds exact provider token counts one module away. A public
    // constructor would let those be wrapped in this type with a heuristic that lies about them,
    // so the only ways in are factories that fix the heuristic to something this phase performs.
    for (Constructor<?> constructor : EstimatedTokenCount.class.getDeclaredConstructors()) {
      assertThat(Modifier.isPrivate(constructor.getModifiers()))
          .as("constructor %s must be private", constructor)
          .isTrue();
    }
    for (Method factory : EstimatedTokenCount.class.getDeclaredMethods()) {
      if (!Modifier.isStatic(factory.getModifiers())
          || !Modifier.isPublic(factory.getModifiers())
          || factory.getReturnType() != EstimatedTokenCount.class) {
        continue;
      }
      assertThat(factory.getParameterTypes())
          .as("factory %s must not let the caller supply the heuristic", factory.getName())
          .doesNotContain(String.class);
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
  void usageHandsBackAnEstimateOnlyThroughTheEstimateType() throws NoSuchMethodException {
    ContextUsage usage = new ContextUsage(2, 9L, 9L);

    // The exact numbers are plain longs. The guess is not: changing this accessor to return a bare
    // number — the mistake the type exists to prevent — fails here.
    assertThat(usage.characters()).isEqualTo(9L);
    assertThat(ContextUsage.class.getDeclaredMethod("estimatedTokens").getReturnType())
        .isEqualTo(EstimatedTokenCount.class);
    assertThat(usage.estimatedTokens().estimatedTokens()).isEqualTo(3L);
    assertThat(usage.estimatedTokens().isExact()).isFalse();
  }

  @Test
  void anImpossibleCharacterCountIsRefused() {
    assertThatThrownBy(() -> EstimatedTokenCount.fromCharacters(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
  }
}
