package com.vibecode.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextPack;
import com.vibecode.context.domain.RedactedContextItem;
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
 *
 * <p>Since CTX-SAFE-01 the same tests cover a second invariant of the same shape: nothing reaches a
 * pack, or a row, without having been through redaction. That was previously a property of the
 * order in which the compiler ran its steps, which is exactly the kind of guarantee this file
 * exists to replace — {@code ContextSafeContentBypassTest} runs the attempts, and these assert the
 * shapes that make them fail to compile.
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
  @DisplayName("An admitted item cannot be built from a bare item, only from a redacted one")
  void anAdmittedItemRequiresRedactedContent() {
    Constructor<?>[] constructors = AdmittedContextItem.class.getDeclaredConstructors();
    assertThat(constructors).hasSize(1);

    assertThat(constructors[0].getParameterTypes())
        .as("the redaction step is not skippable: a raw item does not fit the constructor")
        .contains(RedactedContextItem.class)
        .doesNotContain(ContextItem.class, String.class);
  }

  @Test
  @DisplayName("Redacted content has no public constructor and exactly two named mints")
  void thereAreTwoWaysToDeclareContentRedactedAndBothSayWhichBoundaryTheyAre() {
    for (Constructor<?> constructor : RedactedContextItem.class.getDeclaredConstructors()) {
      assertThat(Modifier.isPrivate(constructor.getModifiers()))
          .as("a public constructor would be the bypass this type exists to remove")
          .isTrue();
    }

    List<Method> factories =
        Arrays.stream(RedactedContextItem.class.getDeclaredMethods())
            .filter(method -> Modifier.isStatic(method.getModifiers()))
            .filter(method -> method.getReturnType().equals(RedactedContextItem.class))
            .filter(method -> !method.isSynthetic())
            .toList();

    // Two, because creation and rehydration are different boundaries and a reader must be able to
    // tell from the call site which one they are looking at. A third would mean a third answer to
    // "what makes this content safe", and no rule anywhere would say what it was.
    assertThat(factories)
        .extracting(Method::getName)
        .containsExactlyInAnyOrder("producedByRedaction", "rehydratedFromStorage");

    for (Method factory : factories) {
      assertThat(factory.getParameterTypes())
          .as("%s must take an item, never raw text: a factory taking a String would hand the"
                  + " boundary back to whoever remembered to call the redactor", factory.getName())
          .containsExactly(ContextItem.class);
    }
  }

  @Test
  @DisplayName("Nothing anywhere in the module takes raw text and returns redacted content")
  void noProductionTypeLaundersAStringIntoRedactedContent() {
    // The shape of the shortcut somebody would actually add: a helper that accepts the text and
    // returns the safe wrapper, so the caller never has to think about the redactor at all. The
    // architecture test fences the return type across the whole application; this one names the
    // specific signature, so a reviewer reading either knows what is being defended.
    for (Class<?> type :
        List.of(
            RedactedContextItem.class,
            AdmittedContextItem.class,
            CompiledContextPack.class,
            ContextPackEntity.class,
            ContextPackItemEntity.class)) {
      for (Method method : type.getDeclaredMethods()) {
        if (method.isSynthetic() || !method.getReturnType().equals(RedactedContextItem.class)) {
          continue;
        }
        assertThat(method.getParameterTypes())
            .as("%s.%s must not turn a String into redacted content", type.getSimpleName(), method.getName())
            .doesNotContain(String.class);
      }
    }
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
