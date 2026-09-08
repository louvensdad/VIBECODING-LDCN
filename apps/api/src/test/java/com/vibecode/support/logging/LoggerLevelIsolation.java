package com.vibecode.support.logging;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;

/**
 * Keeps a test class's logging configuration inside that test class.
 *
 * <p>Add {@code @ExtendWith(LoggerLevelIsolation.class)} to any class that changes a logger level,
 * by whatever route, and the levels the rest of the JVM sees are the ones it would have seen had
 * the class not run. Nothing else is needed: no {@code @AfterEach}, no remembering which of a
 * dozen categories a {@code @TestPropertySource} named.
 *
 * <p>Two scopes, because the two routes mutate at different moments. A property route —
 * {@code @TestPropertySource}, or any {@code logging.level.*} in the environment — is applied once,
 * while Spring builds the application context, which happens before the first test method and not
 * again for the ones after it; undoing it per method would leave the second method running under
 * settings the first one had. So the class scope owns that, and the method scope owns programmatic
 * changes, which a test makes and finishes with inside its own method.
 *
 * <p>What it cannot do is repair a leak from a class that does not carry it. The class snapshot is
 * taken in {@code beforeAll}, so it records whatever is in force when this class starts; if an
 * earlier class left a level raised, that level is the baseline and is faithfully put back.
 * Isolation is a property of every mutating class carrying this, not of any one of them.
 */
public final class LoggerLevelIsolation
    implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

  private static final Namespace NAMESPACE = Namespace.create(LoggerLevelIsolation.class);
  private static final String KEY = "levels";

  @Override
  public void beforeAll(ExtensionContext context) {
    store(context).put(KEY, LoggerLevels.snapshot());
  }

  @Override
  public void afterAll(ExtensionContext context) {
    restoreFrom(context);
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    // Runs after Spring has injected the test instance, and so after a property route has already
    // applied. That is deliberate: this snapshot is the class's own settled state, and restoring
    // to it leaves the class's configuration intact for its remaining methods.
    store(context).put(KEY, LoggerLevels.snapshot());
  }

  @Override
  public void afterEach(ExtensionContext context) {
    restoreFrom(context);
  }

  private void restoreFrom(ExtensionContext context) {
    LoggerLevels before = store(context).get(KEY, LoggerLevels.class);
    if (before != null) {
      before.restore();
    }
  }

  /** The store of the current container or method, so nested classes get their own snapshot. */
  private static Store store(ExtensionContext context) {
    return context.getStore(NAMESPACE);
  }
}
