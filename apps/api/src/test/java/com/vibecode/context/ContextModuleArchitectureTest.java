package com.vibecode.context;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.theClass;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The boundaries of the context module, enforced by the build instead of by whoever remembers to
 * grep.
 *
 * <p>Each rule here replaces a convention that was previously kept only by review. A convention
 * survives exactly as long as the person who knows about it; a failing build outlives them.
 */
class ContextModuleArchitectureTest {

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.vibecode");

  @Test
  void contextNeverReachesTheVault() {
    // Phase rule: secrets are references, never context. The context engine assembles what will be
    // shown to a model, so a compiler that can reach the vault is a compiler that can put a
    // credential in a prompt — and no redaction step downstream can reliably take it back out.
    // The only safe distance is structural: this module cannot see the vault at all.
    noClasses()
        .that()
        .resideInAPackage("..context..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..vault..")
        .because(
            "the context engine must not be able to reach secret material, by any route, ever")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void onlyTheBrainMappingKnowsAboutTheBrainModule() {
    // Scoped to the domain, and no wider. What this protects is that the context *domain* stays a
    // leaf: it models context without knowing what the rest of the system is made of, and the one
    // edge it is allowed — "what does a brain entry mean as context" — lives in a single greppable
    // class instead of being answered differently in three places.
    //
    // The application layer is deliberately outside this rule. Collectors read brain entries;
    // that is their job, and a rule forbidding it would forbid the feature rather than protect
    // anything. What must not happen there is a collector deciding for itself what an entry means,
    // and an import rule was never able to catch that — it cannot tell reading an entry apart from
    // re-mapping one. The guarantee that replaces it is behavioural and lives in
    // AllThirteenBrainTypesSurviveCollectionTest: for every BrainEntryType.values(), the collected
    // item's kind must equal BrainEntryContextMapping.kindOf(type). An invented switch fails that,
    // and so does a fourteenth type nobody has decided about.
    //
    // Matched by name rather than by type because javac emits a synthetic switch-map class
    // (BrainEntryContextMapping$1) that holds the enum references and is not assignable to the
    // class that produced it. The pattern covers the class and its own synthetics, nothing else.
    noClasses()
        .that()
        .resideInAPackage("..context.domain..")
        .and()
        .haveNameNotMatching("com\\.vibecode\\.context\\.domain\\.BrainEntryContextMapping(\\$.*)?")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..brain..")
        .because(
            "the context domain is a leaf apart from BrainEntryContextMapping, the single,"
                + " greppable place where the domain depends on brain")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void theBrainMappingStillHasNoDefaultBranch() {
    // What this actually asserts, so nobody spends an afternoon on it: javac compiles an exhaustive
    // switch expression over an enum with no `default` by emitting a synthetic arm that throws
    // java.lang.MatchException — the case where the enum gained a constant after this class was
    // compiled. Adding a real `default` makes that arm unnecessary, so the reference disappears
    // from the constant pool. The presence of MatchException is therefore a proxy for "the switch
    // is still exhaustive without a fallback", which is what protects a fourteenth BrainEntryType
    // from being silently swallowed.
    //
    // It is a javac codegen signature, not a language guarantee. If a future compiler stops
    // emitting it this rule will fail while the code is still correct — in that case the rule must
    // be REPLACED with an equivalent guard, not deleted. Deleting it removes the only automated
    // check that the mapping has no fallback branch.
    theClass(com.vibecode.context.domain.BrainEntryContextMapping.class)
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.lang.MatchException")
        .because(
            "an exhaustive switch with no default compiles to a MatchException arm; adding a"
                + " default removes it and disarms the compile error that guards a new entry type")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void nothingInContextEvenNamesTheVaultService() {
    // The package rule above already forbids this, and this one overlaps it deliberately. The
    // package rule is the one an over-eager refactor could weaken by moving a class; this one names
    // the single type that would actually do the damage, so a reviewer reading either test knows
    // exactly what is being defended. SECRETS ARE REFERENCES, NEVER CONTEXT.
    noClasses()
        .that()
        .resideInAPackage("..context..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("com.vibecode.vault.application.VaultService")
        .because(
            "resolving a secret is not a step this pipeline has; an item is text from an official"
                + " record, and a SecretReference is never dereferenced to build one")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void onlyTheRedactionStepReachesTheGuardiansRedactor() {
    // One chokepoint, not a habit. Redaction has to happen before persistence, before the digest
    // and before measurement, and the way to keep that true is for there to be exactly one place
    // in this module that can call the redactor at all. A collector that started redacting on its
    // own would look harmless and would move the boundary: the budget would then be measuring text
    // that a later step might redact again, and two items would have been through different
    // treatments with nothing recording which.
    //
    // The rule names the Guardian's class rather than its package, because the context engine has
    // other legitimate business with the guardian - the security summary collector reads findings.
    noClasses()
        .that()
        .resideInAPackage("..context..")
        .and()
        .resideOutsideOfPackage("..context.application.redaction..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("com.vibecode.guardian.domain.SensitiveDataRedactor")
        .because(
            "ContextRedaction is the single point at which context content is redacted, and a"
                + " second caller would mean two answers to when redaction happens")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void contextPersistenceDependsOnTheDomainAndNotOnTheCompiler() {
    // Why the admission model lives in the domain rather than beside the compiler. Persistence has
    // to name the type it stores; if that type lived in the application layer, infrastructure would
    // depend on application while the application already depends on ContextPackRepository - a
    // cycle, and the kind that only shows up as a mysterious startup failure much later.
    //
    // It also keeps a second door shut: an entity that could see the policy would be one refactor
    // away from re-deriving an item's explanation from today's rules on read, which would quietly
    // rewrite what a stored pack says about itself.
    noClasses()
        .that()
        .resideInAPackage("..context.infrastructure..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..context.application..")
        .because(
            "a stored pack is described by the domain; persistence must not need the compiler or"
                + " the policy to say what it holds")
        .check(PRODUCTION_CLASSES);
  }
}
