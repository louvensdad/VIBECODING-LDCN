package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.UrlResource;

/**
 * The other tests in this package prove the pins work. This one proves production has them.
 *
 * <p>A test-classpath application.yml replaces the main one rather than merging with it, so every
 * other test in the suite — including the ones that assert nothing leaks — runs against the test
 * file alone. Delete the whole logging block from src/main/resources/application.yml and they all
 * still pass, because nothing they touch ever reads it. The drift is one-directional: a missing pin
 * in the test file shows up as a leaking test, a missing pin in the production file shows up as
 * nothing at all. This test is the half that was missing.
 *
 * <p>It reads the main build output rather than the source tree, so it checks the file that
 * actually ships and does not depend on the working directory a runner happens to use.
 */
class ProductionLoggingConfigTest {

  /**
   * Every category observed printing a user-supplied object as a matter of course — routinely, on
   * the ordinary path, without anyone asking it to. That rule, stated at the top of the yml block,
   * is what makes the list checkable: three layers handle the same value on one request, and each
   * of them was caught by capturing at TRACE and grouping by logger name rather than by guessing.
   * Adding one here without adding it to both yml files fails; removing one from either yml fails.
   */
  private static final List<String> PINNED =
      List.of(
          // Hibernate 6.6.8.Final, on the way to the database.
          "org.hibernate.orm.jdbc.bind",
          "org.hibernate.orm.jdbc.extract",
          "org.hibernate.internal.util.EntityPrinter",
          "org.hibernate.resource.jdbc.internal.ResourceRegistryStandardImpl",
          // Spring MVC, before the ORM ever sees the value.
          "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor",
          "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor",
          "org.springframework.web.method.HandlerMethod",
          // Bean Validation, on the same request as the two above.
          "org.hibernate.validator.internal.engine.resolver.JPATraversableResolver",
          // The rejected request: the value leaves through the exception, not the body.
          "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver");

  @Test
  @DisplayName("The shipped application.yml pins every value-printing Hibernate category")
  void productionConfigPinsEveryCategory() throws IOException {
    Map<String, Object> production = loggingLevelsOf(mainApplicationYml());

    for (String category : PINNED) {
      // INFO rather than OFF: the categories emit values only below INFO, and an errors-only
      // logger that can still report a genuine problem is worth more than a silenced one.
      assertThat(production)
          .as("logging.level.%s must be pinned in src/main/resources/application.yml", category)
          .containsEntry("logging.level." + category, "INFO");
    }
  }

  @Test
  @DisplayName("The test application.yml pins exactly what production pins, and nothing less")
  void testConfigMatchesProduction() throws IOException {
    Map<String, Object> production = loggingLevelsOf(mainApplicationYml());
    Map<String, Object> test = loggingLevelsOf(testApplicationYml());

    // Equality, not containment. If the two files disagree in either direction the suite is no
    // longer exercising the configuration that ships, and that is worth failing over even when the
    // difference happens to be harmless.
    assertThat(test)
        .as("the two application.yml files must pin the same categories at the same levels")
        .isEqualTo(production);
  }

  private URL mainApplicationYml() throws IOException {
    return applicationYml(url -> !url.contains("test-classes"));
  }

  private URL testApplicationYml() throws IOException {
    return applicationYml(url -> url.contains("test-classes"));
  }

  private URL applicationYml(java.util.function.Predicate<String> matching) throws IOException {
    List<URL> candidates =
        Collections.list(getClass().getClassLoader().getResources("application.yml"));
    List<URL> matches = candidates.stream().filter(u -> matching.test(u.toString())).toList();
    // Both files must be visible and distinguishable. If a build layout change ever collapses them
    // into one, this test must fail loudly rather than quietly checking the same file twice.
    assertThat(matches)
        .as("exactly one application.yml expected among %s", candidates)
        .hasSize(1);
    return matches.get(0);
  }

  private Map<String, Object> loggingLevelsOf(URL url) throws IOException {
    Map<String, Object> levels = new TreeMap<>();
    for (PropertySource<?> source :
        new YamlPropertySourceLoader().load(url.toString(), new UrlResource(url))) {
      for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
        if (name.startsWith("logging.level.")) {
          levels.put(name, source.getProperty(name));
        }
      }
    }
    return levels;
  }
}
