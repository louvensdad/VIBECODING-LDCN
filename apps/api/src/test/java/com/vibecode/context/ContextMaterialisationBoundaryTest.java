package com.vibecode.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextPack;
import com.vibecode.context.infrastructure.persistence.ContextPackEntity;
import com.vibecode.context.infrastructure.persistence.ContextPackItemEntity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That an unadmitted item cannot be materialised, asserted as a shape rather than as a behaviour.
 *
 * <p>The invariant is that nothing reaches a pack, or a row, without a named rule and an
 * explanation behind it. A behavioural test can only ever show that today's code paths honour it;
 * what would actually break it is somebody adding a second, convenient path - a {@code
 * from(ContextPack)} overload, a constructor taking bare items - and no behavioural test would
 * notice, because it would still be passing about the path it knows.
 *
 * <p>So these tests read the type signatures. They are blunt, and that is the point: if a new
 * shortcut is added, the failure names it.
 */
class ContextMaterialisationBoundaryTest {

  @Test
  @DisplayName("A pack entity can only be built from a compiled pack")
  void thereIsOneWayToBuildAPackEntity() {
    List<Method> factories =
        Arrays.stream(ContextPackEntity.class.getDeclaredMethods())
            .filter(method -> Modifier.isStatic(method.getModifiers()))
            .filter(method -> method.getReturnType().equals(ContextPackEntity.class))
            .toList();

    assertThat(factories)
        .as("exactly one factory, so there is one answer to how a pack becomes a row")
        .hasSize(1);
    assertThat(factories.get(0).getParameterTypes())
        .as("and it takes the admitted form, never a bare pack")
        .containsExactly(CompiledContextPack.class);
  }

  @Test
  @DisplayName("No public entry point on either entity accepts a bare pack or a bare item")
  void noEntryPointTakesAnUnadmittedItem() {
    for (Class<?> entity : List.of(ContextPackEntity.class, ContextPackItemEntity.class)) {
      for (Method method : entity.getDeclaredMethods()) {
        if (method.isSynthetic()) {
          continue;
        }
        assertThat(method.getParameterTypes())
            .as("%s.%s must not take a pack that has no admissions", entity.getSimpleName(), method.getName())
            .doesNotContain(ContextPack.class);
      }
      for (Constructor<?> constructor : entity.getDeclaredConstructors()) {
        if (constructor.isSynthetic()) {
          continue;
        }
        assertThat(constructor.getParameterTypes())
            .as("%s must not be constructible from an unadmitted item", entity.getSimpleName())
            .doesNotContain(ContextPack.class, ContextItem.class);
      }
    }
  }

  @Test
  @DisplayName("A compiled pack is built from admitted items and from nothing else")
  void aCompiledPackTakesOnlyAdmittedItems() {
    Constructor<?>[] constructors = CompiledContextPack.class.getDeclaredConstructors();
    assertThat(constructors).hasSize(1);

    Constructor<?> canonical = constructors[0];
    assertThat(canonical.getParameterTypes())
        .as("no pre-built pack may be supplied alongside the items; pack() is derived from them")
        .doesNotContain(ContextPack.class);

    // The list component is checked through its generic type, because erasure would let a
    // List<ContextItem> pass a check on the raw parameter type.
    List<Type> listArguments =
        Arrays.stream(canonical.getGenericParameterTypes())
            .filter(ParameterizedType.class::isInstance)
            .map(ParameterizedType.class::cast)
            .flatMap(parameterized -> Arrays.stream(parameterized.getActualTypeArguments()))
            .toList();

    assertThat(listArguments).contains(AdmittedContextItem.class);
    assertThat(listArguments).doesNotContain(ContextItem.class);
  }

  @Test
  @DisplayName("An item row is built from an admitted item, so a row cannot exist without a reason")
  void anItemRowRequiresAnAdmission() {
    List<Constructor<?>> withArguments =
        Arrays.stream(ContextPackItemEntity.class.getDeclaredConstructors())
            .filter(constructor -> constructor.getParameterCount() > 0)
            .toList();

    assertThat(withArguments).hasSize(1);
    assertThat(withArguments.get(0).getParameterTypes()).contains(AdmittedContextItem.class);
  }
}
