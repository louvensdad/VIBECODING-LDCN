package com.vibecode.context.security;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A secret is a reference, and a reference is never context.
 *
 * <p>The existing architecture test says the context module does not depend on {@code ..vault..} and
 * does not name {@code VaultService}. That is the door. This is the rest of the building: the secret
 * material type, the reference type, the encryption service, the key provider, the provider-account
 * types that hold a credential id, and the cryptographic vocabulary — nonce, wrapped data key,
 * master key.
 *
 * <p><b>Direct and transitive are two different rules, and both are here.</b> ArchUnit's {@code
 * dependOnClassesThat()} reads a class's own constant pool, so it sees the classes a context class
 * names and nothing further. The rules below that use it are therefore checks on the door itself:
 * no class in {@code ..context..} may name secret material, a secret reference, the vault services
 * or a provider account. That is a real rule and it catches the obvious route, but it says nothing
 * about a class in an allowed package that holds a {@code VaultService} and hands back a decrypted
 * value — {@code context → someFacade → vault} satisfies every direct rule while being exactly the
 * route that must not exist. {@link #contextCannotReachTheVaultByAnyRouteAtAll()} closes that: it
 * walks the dependency graph from {@code ..context..} to fixpoint and asserts the vault is not in
 * the reachable set at any depth.
 *
 * <p>Two of the rules below deliberately do not go through ArchUnit's dependency graph. {@code
 * SecretReference} is a record of two fields, so a method that took one and used only its {@code
 * secretId} could satisfy a dependency check while being exactly the resolution step that must not
 * exist. Those two read the class files instead and look for the vocabulary anywhere in the module,
 * which is coarser and harder to satisfy accidentally.
 *
 * <p>Every rule is checked against production classes only. A test may reference whatever it needs
 * to state a limit; production may not.
 */
class ContextSecretMaterialIsolationTest {

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.vibecode");

  @Test
  @DisplayName("No class in context names secret material directly")
  void contextNeverTouchesSecretMaterial() {
    noClasses()
        .that()
        .resideInAPackage("..context..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("com.vibecode.vault.domain.SecretMaterial")
        .because(
            "SecretMaterial is a decrypted secret held in memory; a context item is text from an"
                + " official record and there is no case in which the two meet")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  @DisplayName("No class in context names a secret reference directly")
  void contextNeverHoldsASecretReference() {
    noClasses()
        .that()
        .resideInAPackage("..context..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("com.vibecode.vault.domain.SecretReference")
        .because(
            "a reference is the only form a secret takes outside the vault, and the way to be sure"
                + " the engine never dereferences one is for it never to hold one")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  @DisplayName("Nothing in context depends on encryption, key provision or the vault's exceptions")
  void contextNeverTouchesTheCryptographicMachinery() {
    for (String forbidden :
        List.of(
            "com.vibecode.vault.application.SecretEncryptionService",
            "com.vibecode.vault.application.VaultService",
            "com.vibecode.vault.domain.KeyEncryptionProvider",
            "com.vibecode.vault.domain.SecretRecord",
            "com.vibecode.vault.domain.SecretVersion",
            "com.vibecode.vault.domain.SecretPurpose",
            "com.vibecode.vault.domain.VaultCryptographyException",
            "com.vibecode.vault.infrastructure.LocalKeyEncryptionProvider",
            "com.vibecode.vault.infrastructure.VaultProperties")) {
      noClasses()
          .that()
          .resideInAPackage("..context..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName(forbidden)
          .because("the context engine has no cryptographic step and must not acquire one")
          .check(PRODUCTION_CLASSES);
    }
  }

  @Test
  @DisplayName("Nothing in context reaches a provider account or the credential id it carries")
  void contextNeverReachesAProviderCredential() {
    noClasses()
        .that()
        .resideInAPackage("..context..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..provider..")
        .because(
            "a provider account exists to point at a stored credential; a module that cannot see"
                + " the account cannot follow the pointer")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  @DisplayName("Nothing reachable from context, at any depth, is in the vault or a provider package")
  void contextCannotReachTheVaultByAnyRouteAtAll() {
    // The rule the four above cannot state. A direct-dependency check is defeated by one ordinary
    // class in an allowed package: give it a VaultService field and a method returning a decrypted
    // value, have a collector call that method, and every rule above stays green while vault
    // plaintext walks into a context item. This closes the graph instead of checking one edge.
    //
    // The walk stays inside com.vibecode because that is what was imported: a class outside the
    // imported packages arrives as a stub with no dependencies of its own, so the JDK and Spring
    // are boundaries rather than an explosion. That is a property of the importer above, and if
    // the import ever widens, this becomes a slow test rather than an unsound one.
    Map<String, String> reachedVia = new LinkedHashMap<>();
    Deque<JavaClass> pending = new ArrayDeque<>();
    for (JavaClass inContext : PRODUCTION_CLASSES) {
      if (inContext.getPackageName().startsWith("com.vibecode.context")) {
        reachedVia.put(inContext.getName(), null);
        pending.add(inContext);
      }
    }
    assertThat(reachedVia)
        .as("the walk must have started somewhere, or an empty closure would read as clean")
        .hasSizeGreaterThan(30);

    while (!pending.isEmpty()) {
      JavaClass current = pending.poll();
      for (Dependency dependency : current.getDirectDependenciesFromSelf()) {
        // Base component type, so a String[] field or a List<SecretMaterial>[] is followed to the
        // element rather than stopping at the array type.
        JavaClass target = dependency.getTargetClass().getBaseComponentType();
        String name = target.getName();
        if (!name.startsWith("com.vibecode") || reachedVia.containsKey(name)) {
          continue;
        }
        reachedVia.put(name, current.getName());
        pending.add(target);
      }
    }

    List<String> forbiddenRoutes = new ArrayList<>();
    for (String reached : reachedVia.keySet()) {
      if (reached.startsWith("com.vibecode.vault") || reached.startsWith("com.vibecode.provider")) {
        forbiddenRoutes.add(routeTo(reached, reachedVia));
      }
    }
    assertThat(forbiddenRoutes)
        .as(
            "a secret is a reference and a reference is never context, so no chain of calls of any"
                + " length may lead from the context engine to the vault or to a provider account."
                + " Each line below is the route, from the context class that starts it.")
        .isEmpty();
  }

  @Test
  @DisplayName("The closure walk really walks: from the vault, it reaches the classes it should")
  void theClosureWalkIsNotBlind() {
    // Without this, a walk that followed no edges at all — a typo in the traversal, an importer
    // that returned stubs — would report a clean closure for every module in the application.
    Map<String, String> reachedVia = closureFrom("com.vibecode.vault");
    assertThat(reachedVia)
        .as("the same walk, started at the vault, must reach past the vault's own package")
        .anySatisfy((reached, ignored) -> assertThat(reached).doesNotStartWith("com.vibecode.vault"));

    // And it must reach the vault from a module that legitimately uses it, or "context does not
    // reach the vault" would be a statement about the walk rather than about context.
    assertThat(closureFrom("com.vibecode.provider").keySet())
        .as("the provider module holds credential references, so the walk finds the vault from it")
        .anyMatch(reached -> reached.startsWith("com.vibecode.vault"));
  }

  /** Every {@code com.vibecode} class reachable from a package, mapped to the class that reached it. */
  private static Map<String, String> closureFrom(String rootPackage) {
    Map<String, String> reachedVia = new LinkedHashMap<>();
    Deque<JavaClass> pending = new ArrayDeque<>();
    for (JavaClass root : PRODUCTION_CLASSES) {
      if (root.getPackageName().startsWith(rootPackage)) {
        reachedVia.put(root.getName(), null);
        pending.add(root);
      }
    }
    while (!pending.isEmpty()) {
      JavaClass current = pending.poll();
      for (Dependency dependency : current.getDirectDependenciesFromSelf()) {
        JavaClass target = dependency.getTargetClass().getBaseComponentType();
        String name = target.getName();
        if (!name.startsWith("com.vibecode") || reachedVia.containsKey(name)) {
          continue;
        }
        reachedVia.put(name, current.getName());
        pending.add(target);
      }
    }
    return reachedVia;
  }

  /** The chain that led to a class, read back out of the breadth-first walk that found it. */
  private static String routeTo(String reached, Map<String, String> reachedVia) {
    StringBuilder route = new StringBuilder(reached);
    String previous = reachedVia.get(reached);
    while (previous != null) {
      route.insert(0, previous + " -> ");
      previous = reachedVia.get(previous);
    }
    return route.toString();
  }

  @Test
  @DisplayName("No class in context so much as spells a cryptographic term")
  void theModuleDoesNotEvenUseTheVocabulary() {
    // Coarser than a dependency rule and harder to satisfy by accident: this reads every field,
    // method, parameter and referenced type name in the module. A helper that took a byte[] called
    // wrappedDataKey would pass every rule above and fail this one.
    Set<String> forbidden =
        Set.of(
            "secretmaterial",
            "secretreference",
            "wrappeddatakey",
            "datakey",
            "masterkey",
            "keyencryption",
            "ciphertext",
            "plaintextsecret",
            "decrypt",
            "nonce");

    List<String> offenders = new ArrayList<>();
    for (JavaClass inContext : PRODUCTION_CLASSES) {
      if (!inContext.getPackageName().startsWith("com.vibecode.context")) {
        continue;
      }
      for (String name : vocabularyOf(inContext)) {
        String lowered = name.toLowerCase(java.util.Locale.ROOT);
        for (String term : forbidden) {
          if (lowered.contains(term)) {
            offenders.add(inContext.getName() + " uses " + name);
          }
        }
      }
    }
    assertThat(offenders)
        .as("secrets are references, never context, and the module does not have the words for one")
        .isEmpty();
  }

  @Test
  @DisplayName("The vocabulary rule is looking at something: it finds the terms where they do live")
  void theVocabularyRuleIsNotBlind() {
    // Without this, a typo in the rule above — a package filter that matches nothing, a term list
    // that never matches — would read as a clean module.
    List<String> found = new ArrayList<>();
    for (JavaClass anywhere : PRODUCTION_CLASSES) {
      if (!anywhere.getPackageName().startsWith("com.vibecode.vault")) {
        continue;
      }
      for (String name : vocabularyOf(anywhere)) {
        if (name.toLowerCase(java.util.Locale.ROOT).contains("wrappeddatakey")
            || name.toLowerCase(java.util.Locale.ROOT).contains("secretmaterial")) {
          found.add(anywhere.getSimpleName() + "." + name);
        }
      }
    }
    assertThat(found)
        .as("the same scan run over the vault must find the terms the context scan reported none of")
        .isNotEmpty();

    long contextClasses =
        PRODUCTION_CLASSES.stream()
            .filter(each -> each.getPackageName().startsWith("com.vibecode.context"))
            .count();
    assertThat(contextClasses)
        .as("and the context scan must have had classes to scan")
        .isGreaterThan(30L);
  }

  /** Every name a class states in its own signature surface: fields, methods, parameters, types. */
  private static List<String> vocabularyOf(JavaClass inspected) {
    List<String> names = new ArrayList<>();
    inspected.getFields().forEach(field -> names.add(field.getName()));
    inspected.getFields().forEach(field -> names.add(field.getRawType().getSimpleName()));
    inspected
        .getMethods()
        .forEach(
            method -> {
              names.add(method.getName());
              names.add(method.getRawReturnType().getSimpleName());
              method.getRawParameterTypes().forEach(type -> names.add(type.getSimpleName()));
            });
    inspected
        .getConstructors()
        .forEach(
            constructor ->
                constructor.getRawParameterTypes().forEach(type -> names.add(type.getSimpleName())));
    inspected.getDirectDependenciesFromSelf()
        .forEach(dependency -> names.add(dependency.getTargetClass().getSimpleName()));
    return names;
  }
}
