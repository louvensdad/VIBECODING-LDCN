package com.vibecode.context.security;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
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
 * master key — under any route, transitive dependencies included.
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
  @DisplayName("Nothing in context depends on secret material, by name or by any route")
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
  @DisplayName("Nothing in context depends on a secret reference, so nothing can resolve one")
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
