package com.vibecode.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
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
 * <p><b>What would have to be true for this test to fail.</b> Any class carrying an
 * {@code @ExceptionHandler} anywhere in {@code src/main} building a {@code ResponseEntity} with a
 * body — through {@code body(...)}, {@code ok(body)}, or the {@code new ResponseEntity<>(body, ...)}
 * constructor — or an {@code @ExceptionHandler} returning a bare {@link ApiError} under a
 * {@code @ResponseStatus}. That first mutation was applied, the original {@code InvalidLimitAdvice}
 * body restored, and this test named the method.
 *
 * <p><b>Review finding R3-D: what this rule is, and what it is not.</b> It was written per-method
 * over {@code getMethodCallsFromSelf()}, and review defeated it twice — by moving the
 * {@code .body(...)} into a private helper the handler calls, and by writing
 * {@code new ResponseEntity<>(body, BAD_REQUEST)}, which is a constructor call and therefore
 * invisible to a method-call detector. Both bypasses passed the rule while its own javadoc claimed
 * that "matching the family is what stops a caller sliding out of the rule by rearranging the
 * chain". It did not.
 *
 * <p>A third was found afterwards and is also closed: {@code ResponseEntity.of(ProblemDetail)},
 * the idiomatic Spring 6 spelling of this handler, which carries status and body in one call named
 * neither {@code body} nor {@code ok} — and whose return type the bare-return check did not know
 * about either, so it was invisible twice over.
 *
 * <p>It is now scoped to the <b>class</b> rather than the method, and looks at constructor calls,
 * at three body-carrying factory names and at three body-carrying return types. What it still
 * cannot see is a helper in a <em>different</em> class, or a handler that writes to the
 * {@code HttpServletResponse} itself. So
 * the honest claim is the narrow one: <b>this rule documents the boundary and catches the
 * near-misses; the behavioural tests are what hold it.</b> Under the private-helper bypass this
 * rule was silent and {@code ContextHttpErrorSurfaceTest} still failed with three failures and an
 * error; under the {@code ResponseEntity.of} bypass it was silent again and the same suite failed
 * seven times. Those tests send requests, and a bypass has to survive an actual write to a caller
 * who cannot read it. A structural rule cannot make that guarantee, and this one no longer says it
 * can — every widening in it so far has been a blind spot someone found, which is the strongest
 * available argument for not treating it as the guard.
 */
class OneErrorResponseBoundaryTest {

  /**
   * Return types that are an error body in their own right.
   *
   * <p>{@code ProblemDetail} and {@code ErrorResponse} are here because of R4-D. A handler returning
   * either one, with {@code @ResponseStatus} supplying the status, writes a body without ever
   * touching a {@code ResponseEntity} — so neither the call detector nor a check that knew only
   * about {@link ApiError} would see it.
   */
  private static final Set<String> BODY_RETURN_TYPES =
      Set.of(
          "com.vibecode.shared.web.ApiError",
          "org.springframework.http.ProblemDetail",
          "org.springframework.web.ErrorResponse");

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

    // Scoped to the class, not the method: review defeated the per-method version by moving the
    // .body(...) one call deeper into a private helper, which is a refactor anybody might make for
    // reasons that have nothing to do with this rule.
    List<String> buildTheirOwnBody =
        PRODUCTION_CLASSES.stream()
            .filter(
                type ->
                    type.getMethods().stream()
                        .anyMatch(method -> method.isAnnotatedWith(ExceptionHandler.class)))
            .flatMap(type -> type.getMethods().stream())
            .filter(OneErrorResponseBoundaryTest::buildsAResponseEntityWithABody)
            .map(JavaMethod::getFullName)
            .sorted()
            .toList();

    // And the shape that carries a body without a ResponseEntity at all: a handler returning the
    // DTO directly, its status supplied by @ResponseStatus. Nothing writes one today, and a rule
    // that only knew about ResponseEntity would not notice the first one.
    // And the shapes that carry a body without a ResponseEntity at all: a handler returning the DTO
    // directly, its status supplied by @ResponseStatus. R4-D widened this beyond ApiError —
    // checking one type meant a handler returning ProblemDetail or ErrorResponse was invisible
    // twice over, once here and once in the call detector.
    List<String> returnABodyDirectly =
        PRODUCTION_CLASSES.stream()
            .flatMap(type -> type.getMethods().stream())
            .filter(method -> method.isAnnotatedWith(ExceptionHandler.class))
            .filter(method -> BODY_RETURN_TYPES.contains(method.getReturnType().getName()))
            .map(JavaMethod::getFullName)
            .sorted()
            .toList();
    assertThat(returnABodyDirectly)
        .as(
            "an @ExceptionHandler returning a body object directly bypasses the boundary the same"
                + " way, with the status supplied by @ResponseStatus instead of by a builder")
        .isEmpty();

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
            .filter(OneErrorResponseBoundaryTest::buildsAResponseEntityWithABody)
            .map(JavaMethod::getName)
            .sorted()
            .toList();

    assertThat(found)
        .as("the boundary is the one place that writes a body, and the detector must see it")
        .containsExactly("respond");
  }

  /**
   * Whether a method builds a {@code ResponseEntity} that carries a body.
   *
   * <p>Three shapes, because review found the first version of this saw only one of them:
   *
   * <ul>
   *   <li>{@code body(x)} on a builder, the fluent chain;
   *   <li>{@code ResponseEntity.ok(x)} and {@code ResponseEntity.of(x)}, static factories that take
   *       the body directly and never touch a builder. {@code of(ProblemDetail)} is review finding
   *       R4-D and is the idiomatic Spring 6 way to write exactly this handler — it carries the
   *       status and the body in one call, under a name that is neither {@code body} nor {@code ok};
   *   <li>{@code new ResponseEntity<>(x, status)}, a <em>constructor</em> call, which
   *       {@code getMethodCallsFromSelf()} does not return at all. That was the second bypass, and
   *       it is the one that falsified the previous version of this comment.
   * </ul>
   *
   * <p>The owner is matched on name prefix rather than on an exact class, because the declared
   * owner is {@code ResponseEntity$BodyBuilder} for a chain, {@code ResponseEntity$HeadersBuilder}
   * in other shapes, and {@code ResponseEntity} itself for the factories and the constructor.
   */
  /**
   * The {@code ResponseEntity} members that carry a body. {@code of} is here because of R4-D:
   * {@code ResponseEntity.of(ProblemDetail)} is a single call carrying status and body, named
   * neither {@code body} nor {@code ok}, and it is what a reviewer reaching for idiomatic Spring 6
   * would write. It also covers {@code of(Optional)}.
   */
  private static final Set<String> BODY_CARRYING = Set.of("body", "ok", "of");

  private static boolean buildsAResponseEntityWithABody(JavaMethod method) {
    for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
      if (BODY_CARRYING.contains(call.getName())
          && isResponseEntity(call.getTargetOwner().getName())) {
        return true;
      }
    }
    for (JavaConstructorCall call : method.getConstructorCallsFromSelf()) {
      if (isResponseEntity(call.getTargetOwner().getName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isResponseEntity(String owner) {
    return owner.startsWith("org.springframework.http.ResponseEntity");
  }
}
