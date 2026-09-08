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
    // Context sits above brain, so the dependency direction is legitimate — but only in one place.
    // Confined to a single class, "what does a brain entry mean as context" has one answer that can
    // be reviewed; spread across the module it becomes several answers that quietly disagree.
    // Matched by name rather than by type because javac emits a synthetic switch-map class
    // (BrainEntryContextMapping$1) that holds the enum references and is not assignable to the
    // class that produced it. The pattern covers the class and its own synthetics, nothing else.
    noClasses()
        .that()
        .resideInAPackage("..context..")
        .and()
        .haveNameNotMatching("com\\.vibecode\\.context\\.domain\\.BrainEntryContextMapping(\\$.*)?")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..brain..")
        .because(
            "BrainEntryContextMapping is the single, greppable place where context depends on brain")
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
}
