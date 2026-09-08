package com.vibecode.support.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.springframework.test.context.TestPropertySource;

/**
 * The isolation guarantee, enforced by the build instead of by whoever remembers to grep.
 *
 * <p>{@link LoggerLevelIsolation} works, but only for the classes that carry it: it puts back what
 * it saw, and it never saw a class that did not declare it. So the guarantee is a property of
 * every mutating class carrying the extension, and today every one of them does — which is exactly
 * the kind of fact that is true until the next test is written. A logging test that forgets the
 * annotation does not fail; it quietly hands the classes after it a different baseline, and they
 * see less of a leak than they set up for. Nothing about that looks like a bug from the outside,
 * which is why it belongs to the build rather than to review.
 *
 * <p>Two ways to mutate, so two things to look for: a call to Logback's {@code setLevel}, and a
 * {@code logging.level.*} in a {@code @TestPropertySource}. Both are checked through the class's
 * superclasses, because a scenario body inherited from elsewhere mutates just as effectively as
 * one written in place.
 *
 * <p>What it does not catch, said plainly rather than left to be discovered: a mutation reached
 * through a helper class rather than made directly — {@code LogCapture} is exactly that, and it
 * restores what it raises — and an extension registered by any route other than {@code @ExtendWith}
 * on the class, its enclosing class or a superclass. Both are narrow, and both fail towards asking
 * for the annotation rather than towards silence.
 */
class LoggerLevelIsolationArchitectureTest {

  /** Tests included on purpose: the classes this rule is about are all test classes. */
  private static final JavaClasses ALL_CLASSES =
      new ClassFileImporter().importPackages("com.vibecode");

  private static final String LOGBACK_LOGGER = "ch.qos.logback.classic.Logger";

  @Test
  @DisplayName("Every test class that changes a logger level is isolated")
  void everyMutatingTestClassCarriesTheExtension() {
    List<String> offenders = new ArrayList<>();
    for (JavaClass candidate : ALL_CLASSES) {
      if (!isRunnableTestClass(candidate) || !mutatesLoggerLevels(candidate)) {
        continue;
      }
      if (!isIsolated(candidate)) {
        offenders.add(candidate.getName() + " — " + whyItMutates(candidate));
      }
    }

    assertThat(offenders)
        .as(
            "a test class that changes a logger level must be annotated"
                + " @ExtendWith(LoggerLevelIsolation.class), or it leaves that level in force for"
                + " every class that runs after it in the same JVM")
        .isEmpty();
  }

  @Test
  @DisplayName("The rule is looking at something: it finds the classes that do mutate")
  void theRuleActuallyMatchesTheKnownMutatingClasses() {
    // A rule that matches nothing passes forever. This pins the population it is checking, so a
    // refactor that moves the mutating tests out of the importer's reach fails here rather than
    // turning the rule above into a no-op nobody notices.
    List<String> mutating = new ArrayList<>();
    for (JavaClass candidate : ALL_CLASSES) {
      if (isRunnableTestClass(candidate) && mutatesLoggerLevels(candidate)) {
        mutating.add(candidate.getSimpleName());
      }
    }

    assertThat(mutating)
        .contains(
            "HibernateValueLoggingUnderDebugTest",
            "SecretLoggingTest",
            "ContextPackPlaintextLeakTest",
            "LoggerLevelsRestoreTest",
            "SpringPropertyLoggerLevelMutationTest");
  }

  /** A class JUnit will actually run: concrete, and carrying a test somewhere in its hierarchy. */
  private static boolean isRunnableTestClass(JavaClass candidate) {
    if (candidate.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT)) {
      return false;
    }
    for (JavaMethod method : candidate.getAllMethods()) {
      if (method.isAnnotatedWith(Test.class)) {
        return true;
      }
    }
    return false;
  }

  private static boolean mutatesLoggerLevels(JavaClass candidate) {
    return whyItMutates(candidate) != null;
  }

  /** The reason, so a failure names the route rather than only the class. */
  private static String whyItMutates(JavaClass candidate) {
    for (JavaClass inHierarchy : hierarchyOf(candidate)) {
      for (JavaMethodCall call : inHierarchy.getMethodCallsFromSelf()) {
        if (call.getTargetOwner().getName().equals(LOGBACK_LOGGER)
            && call.getName().equals("setLevel")) {
          return "calls " + LOGBACK_LOGGER + ".setLevel in " + inHierarchy.getSimpleName();
        }
      }
      Optional<TestPropertySource> properties =
          inHierarchy.tryGetAnnotationOfType(TestPropertySource.class);
      if (properties.isPresent()) {
        for (String property : properties.get().properties()) {
          if (property.startsWith("logging.level.")) {
            return "declares " + property + " in " + inHierarchy.getSimpleName();
          }
        }
      }
    }
    return null;
  }

  /**
   * The extension counts wherever JUnit would find it: on the class, on a superclass, or on an
   * enclosing class, which is how a {@code @Nested} container inherits one.
   */
  private static boolean isIsolated(JavaClass candidate) {
    for (JavaClass inHierarchy : hierarchyOf(candidate)) {
      JavaClass enclosing = inHierarchy;
      while (enclosing != null) {
        if (declaresTheExtension(enclosing)) {
          return true;
        }
        enclosing = enclosing.getEnclosingClass().orElse(null);
      }
    }
    return false;
  }

  private static boolean declaresTheExtension(JavaClass candidate) {
    Optional<ExtendWith> extendWith = candidate.tryGetAnnotationOfType(ExtendWith.class);
    if (extendWith.isEmpty()) {
      return false;
    }
    for (Class<? extends Extension> extension : extendWith.get().value()) {
      if (extension.equals(LoggerLevelIsolation.class)) {
        return true;
      }
    }
    return false;
  }

  private static List<JavaClass> hierarchyOf(JavaClass candidate) {
    List<JavaClass> hierarchy = new ArrayList<>();
    hierarchy.add(candidate);
    for (JavaClass parent : candidate.getAllRawSuperclasses()) {
      if (parent.getPackageName().startsWith("com.vibecode")) {
        hierarchy.add(parent);
      }
    }
    return hierarchy;
  }
}
