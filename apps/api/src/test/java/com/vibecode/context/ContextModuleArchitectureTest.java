package com.vibecode.context;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.theClass;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.vibecode.context.application.redaction.ContextRedaction;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.RedactedContextItem;
import com.vibecode.context.infrastructure.persistence.ContextPackItemEntity;
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
  void onlyTheRedactionStepMintsRedactedContent() {
    // The other half of the boundary CTX-SAFE-01 built. The type system already stops a raw
    // ContextItem reaching a pack: AdmittedContextItem takes a RedactedContextItem and nothing
    // else. What the compiler cannot express, without a JPMS module this project does not have, is
    // "one package may call this factory and no other" - so it is expressed here instead, and it
    // fails the build in the same place a broken compile would.
    //
    // Widened to the whole application on purpose rather than scoped to ..context..: a mint
    // reached from outside the module would be worse than one reached from inside it, and a rule
    // that only looked inward would have missed it.
    //
    // THIS RULE IS THE MESSAGE, NOT THE BOUNDARY, and the distinction is load-bearing rather than
    // stylistic. callMethod matches a JavaMethodCall and nothing else, so
    // `RedactedContextItem::producedByRedaction` - a method reference, modelled as a
    // JavaMethodReference - passes it. That blindness is known and left in place deliberately: the
    // predicate is kept narrow so its failure names the exact call a reader is being warned off,
    // in a sentence about redaction rather than about dependencies.
    //
    // It is safe to leave narrow ONLY because the dependency fence below covers the same ground by
    // a mechanism that has no such blind spot, including inside the four allowlisted files. When
    // the fence exempted whole files rather than classes, this rule was the only guard over their
    // contents, and a nested class minting by method reference passed the entire suite. Do not let
    // this rule become the only guard over anything again.
    noClasses()
        .that()
        .resideOutsideOfPackage("..context.application.redaction..")
        .should()
        .callMethod(RedactedContextItem.class, "producedByRedaction", ContextItem.class)
        .because(
            "ContextRedaction is the only step that may declare content redacted; a second caller"
                + " would be a second answer to whether redaction ran")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void onlyPersistenceRehydratesStoredContent() {
    // Rehydration is a different boundary from creation and is kept to the one place that has the
    // standing to use it. What makes it defensible there is not that the text is checked - nothing
    // re-redacts on read - but that the row was written by this application after redaction ran,
    // and there is no column that could hold a pre-redaction value. Anywhere else, the same call
    // would be a way to declare arbitrary text safe by asserting it came from a database.
    noClasses()
        .that()
        .resideOutsideOfPackage("..context.infrastructure.persistence..")
        .should()
        .callMethod(RedactedContextItem.class, "rehydratedFromStorage", ContextItem.class)
        .because(
            "trusting a row is only honest where the row is one we wrote; everywhere else it is a"
                + " way to launder raw text into a pack")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void nothingOutsideTheAllowlistMayEvenNameRedactedContent() {
    // THIS RULE IS THE BOUNDARY. The two above are the message: they name the exact call a reader
    // is being warned off, and they fail with a sentence about redaction rather than about
    // dependencies. Keep both, but if the two ever disagree with this one, this one is right.
    //
    // Why it is a dependency fence and not a check on calls or on return types. The first version
    // of this rule fenced the declared return type and the two above fenced the calls, and a
    // review got past all three with eleven ordinary lines in this very package:
    //
    //     private static final Function<ContextItem, RedactedContextItem> MINT =
    //         RedactedContextItem::producedByRedaction;
    //
    // A method reference compiles to invokedynamic, which ArchUnit models as a JavaMethodReference
    // and not a JavaMethodCall, so callMethod(...) never fired; and the class declared no method
    // returning the type, so the return-type rule never fired either. No reflection, no
    // setAccessible - a launderer with a Function field, and the raw fixture reached
    // context_pack_items.
    //
    // The fix is deliberately NOT "also match method references". Enumerating access kinds is open
    // by construction: the next shape nobody thought of gets through exactly the way this one did.
    // A dependency fence is closed by construction - naming the type at all, by any mechanism the
    // JVM has or gains, is a dependency - so the question becomes "who may know this type exists",
    // which has a short and reviewable answer.
    //
    // That answer is enumerated by class rather than by package or pattern, because each entry is
    // there for its own reason and a pattern would silently admit the next class to match it.
    //
    // AND THE FIRST ATTEMPT TO WRITE THAT DOWN WAS ITSELF A PATTERN. It used doNotBelongToAnyOf,
    // whose semantics are "these classes OR any inner, anonymous or nested class of them" - so the
    // allowlist was four FILES, and the pattern was "anything declared inside these four files".
    // The review walked through it in three steps, none of them reflective:
    //
    //     1. a public nested class inside AdmittedContextItem.java minting by direct call:
    //        the fence did not fire at all, and only onlyTheRedactionStepMintsRedactedContent
    //        caught it;
    //     2. the same nested class minting by method reference instead: nine rules green, because
    //        the fence exempted it as nested and rule 1 is blind to method references;
    //     3. a fifth class in application.compiler calling that nested laundry - its own call
    //        descriptor is (ContextItem, ContextAdmission) -> AdmittedContextItem, so it depends on
    //        RedactedContextItem nowhere. Nine rules green, fixture in context_pack_items.
    //
    // Hence doNotHaveFullyQualifiedName below, one per class, which excludes exactly the four named
    // types and nothing declared inside them. It is worth more than the fix that the belief which
    // failed was "enumerated by class": the next person to write an allowlist here will reach for
    // belongToAnyOf for the same reason, and it will admit nested types for them too.
    //
    // Scoped to the whole application rather than to ..context.., which costs nothing and removes
    // a caveat: a class in another module naming this type would be a launderer with a longer
    // import, and there is no legitimate reason for one to exist.
    noClasses()
        .that()
        // The type itself.
        .doNotHaveFullyQualifiedName("com.vibecode.context.domain.RedactedContextItem")
        .and()
        // Mints on the materialisation path. The one step allowed to say content is redacted.
        .doNotHaveFullyQualifiedName("com.vibecode.context.application.redaction.ContextRedaction")
        .and()
        // Mints on rehydration. The one place holding a row it wrote itself.
        .doNotHaveFullyQualifiedName(
            "com.vibecode.context.infrastructure.persistence.ContextPackItemEntity")
        .and()
        // Holds one. It cannot produce one - its only constructor demands it be handed one - so it
        // needs to name the type without being able to supply it.
        .doNotHaveFullyQualifiedName("com.vibecode.context.domain.AdmittedContextItem")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("com.vibecode.context.domain.RedactedContextItem")
        .because(
            "a redacted item is minted at one boundary and rehydrated at one other, and outside"
                + " those four types nothing may declare it, call a method on it, or reference one"
                + " of its methods - which is every way of obtaining one that does not already have"
                + " one in hand")
        .check(PRODUCTION_CLASSES);

    // WHAT THIS RULE DOES NOT CATCH, and why it is still the boundary. ArchUnit's dependency model
    // carries neither the return type of a call nor the parameter types of a constructor call, so
    // a wrapper can flow through a class invisibly:
    //
    //     var wrapper = legit.redactedItem();
    //     return new AdmittedContextItem(wrapper, allow);
    //
    // ContextPackCompiler is exactly that shape - it funnels a wrapper from ContextRedaction into
    // a new AdmittedContextItem, is not allowlisted, and is correctly not flagged. The reason that
    // is a gap in this rule's *description* and not in the boundary is worth stating, because the
    // argument lives nowhere else in the code: producing a wrapper over RAW content needs either a
    // call whose owner is RedactedContextItem or a method reference to one of its mints, and both
    // are dependencies on the owner, so both are caught. An inference-only flow has no way to make
    // a wrapper - it can only pass along one that already exists, and one that already exists was
    // either redacted or read from a row we wrote.
    //
    // The previous version of this rule carried a `.doNotHaveName("redactedItem")` exemption for
    // AdmittedContextItem's record accessor. It was a codebase-wide exemption on a method NAME:
    // any production class anywhere could have declared `RedactedContextItem redactedItem()` and
    // passed. It is deleted rather than narrowed - the allowlist above names that class, so the
    // accessor is covered, and there is nothing left to exempt.
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
