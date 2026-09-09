package com.vibecode.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * There is exactly one place in this application that decides whether an error body can be written
 * for the caller in front of us, and this is what keeps it at one.
 *
 * <p><b>Why a build rule and not a comment.</b> FINDING CTX-09B-3b was not a bug in anybody's
 * logic. {@code ApiExceptionHandler} was fixed to stop handing Spring a body Spring cannot write;
 * the fix lived in a <em>private</em> method; {@code
 * ContextPackController.InvalidLimitAdvice} was written independently, could not reach it, and did
 * the obvious thing — {@code ResponseEntity.status(BAD_REQUEST).body(...)}. That one line put a 500
 * back on a client error, on a real container, for any caller sending {@code Accept:
 * application/xml}. Both pieces of work were correct in isolation and the defect existed only once
 * they were merged, which is precisely the class of defect that review does not catch and a
 * convention does not survive.
 *
 * <p>So the convention is enforced: <b>no method annotated {@code @ExceptionHandler} may build its
 * own response body.</b> Every one of them returns what {@link ApiErrorResponder} produced, and the
 * negotiation decision is therefore made once, in a class whose name says what it is for.
 *
 * <p><b>The rule has no exemption, deliberately.</b> {@code ApiExceptionHandler#notAcceptable} has
 * a branch that only runs when the caller demonstrably accepts what we write, where calling {@code
 * .body(...)} directly would be perfectly safe. It goes through the responder anyway. An exemption
 * is what the next handler copies, and "this one is fine because of a condition three lines up" is
 * not a property a build can check.
 *
 * <p><b>What would have to be true for this test to fail.</b> Any {@code @ExceptionHandler}
 * anywhere in {@code src/main} calling {@code ResponseEntity.BodyBuilder#body}. That mutation was
 * applied — the original {@code InvalidLimitAdvice} body, restored — and this test named the method
 * before the fix was put back.
 */
class OneErrorResponseBoundaryTest {

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.vibecode");

  @Test
  @DisplayName("No @ExceptionHandler builds its own error body; they all go through the responder")
  void everyExceptionHandlerAnswersThroughTheSharedBoundary() {
    List<String> handlers =
        PRODUCTION_CLASSES.stream()
            .flatMap(type -> type.getMethods().stream())
            .filter(method -> method.isAnnotatedWith(ExceptionHandler.class))
            .map(JavaMethod::getFullName)
            .sorted()
            .toList();

    // Non-vacuity first, and it is not paperwork: this rule is a filter over a set, and a filter
    // over an empty set passes for free. An importer misconfiguration, a package rename or a
    // stereotype change would all leave the assertion below green while checking nothing at all.
    assertThat(handlers)
        .as("the rule below is a filter, and a filter over nothing passes for nothing")
        .hasSizeGreaterThanOrEqualTo(12);
    assertThat(handlers)
        .as("both advices in this application must be in scope, not just the shared one")
        .anyMatch(name -> name.contains("ApiExceptionHandler"))
        .anyMatch(name -> name.contains("InvalidLimitAdvice"));

    List<String> buildTheirOwnBody =
        PRODUCTION_CLASSES.stream()
            .flatMap(type -> type.getMethods().stream())
            .filter(method -> method.isAnnotatedWith(ExceptionHandler.class))
            .filter(OneErrorResponseBoundaryTest::callsBodyOnAResponseEntityBuilder)
            .map(JavaMethod::getFullName)
            .sorted()
            .toList();

    assertThat(buildTheirOwnBody)
        .as(
            "FINDING CTX-09B-3b: an @ExceptionHandler that builds its own body bypasses the Accept"
                + " check, and the write then fails inside the handler — which Spring does not"
                + " re-dispatch, so the exception escapes and a client error is served as a 500."
                + " Return %s.respond(status, body) instead.",
            ApiErrorResponder.class.getSimpleName())
        .isEmpty();
  }

  /**
   * The positive control for the emptiness above.
   *
   * <p>The detector has to be able to see the thing it reports zero of, or its zero is a statement
   * about the detector. This asserts it finds {@code .body(...)} in a method that certainly has one
   * — {@link ApiErrorResponder#respond}, the one place that is <em>supposed</em> to build a body,
   * and the only reason it is not caught by the rule itself is that it carries no
   * {@code @ExceptionHandler}.
   */
  @Test
  @DisplayName("The detector can see a .body(...) call, so its emptiness above means something")
  void theDetectorFindsTheOneBodyCallThatIsMeantToExist() {
    List<String> found =
        PRODUCTION_CLASSES.stream()
            .filter(type -> type.getName().equals(ApiErrorResponder.class.getName()))
            .flatMap(type -> type.getMethods().stream())
            .filter(OneErrorResponseBoundaryTest::callsBodyOnAResponseEntityBuilder)
            .map(JavaMethod::getName)
            .sorted()
            .toList();

    assertThat(found)
        .as("the boundary is the one place that writes a body, and the detector must see it")
        .containsExactly("respond");
  }

  /**
   * Whether a method calls {@code body(...)} on one of Spring's {@code ResponseEntity} builders.
   *
   * <p>Matched on the owner's name prefix rather than on an exact class, because the call site's
   * declared owner is {@code ResponseEntity$BodyBuilder} for a fluent chain and {@code
   * ResponseEntity$HeadersBuilder} or {@code ResponseEntity} itself in other shapes. Matching the
   * family is what stops a caller sliding out of the rule by rearranging the chain.
   */
  private static boolean callsBodyOnAResponseEntityBuilder(JavaMethod method) {
    for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
      if (call.getName().equals("body")
          && call.getTargetOwner().getName().startsWith("org.springframework.http.ResponseEntity")) {
        return true;
      }
    }
    return false;
  }
}
