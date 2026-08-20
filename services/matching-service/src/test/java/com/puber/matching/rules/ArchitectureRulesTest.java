package com.puber.matching.rules;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

/**
 * The structural rules of this service, asserted as ordinary tests.
 *
 * <p>Several rules are vacuously true today: only {@code config} and {@code shared} will exist
 * before Story 1.2, and the feature packages arrive with the stories that first need them. They are
 * written now on purpose. These are the rules four more services inherit, and a rule added after
 * the code it governs is a rule that gets negotiated against existing violations rather than
 * enforced. {@code allowEmptyShould(true)} is therefore deliberate, not a workaround.
 */
@AnalyzeClasses(
        packages = "com.puber.matching",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    private static final String MATCHING = "com.puber.matching.";

    /**
     * AD-8: the domain model is plain Java. Anything framework-flavoured reaching {@code model}
     * means persistence or transport concerns have leaked into the domain.
     */
    @ArchTest
    static final ArchRule modelDependsOnNothingFrameworkFlavoured =
            noClasses()
                    .that()
                    .resideInAPackage("..model..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta..",
                            "javax.persistence..",
                            "com.fasterxml.jackson..",
                            "org.flywaydb..",
                            "org.postgresql..",
                            "io.micrometer..",
                            "org.apache.tomcat..",
                            "com.zaxxer.hikari..")
                    .because("AD-8: the domain model must not depend on the framework")
                    .allowEmptyShould(true);

    /**
     * AD-8: services depend on Strategy interfaces, never on a concrete implementation. Depending
     * on an implementation is what turns a Strategy back into a hard-wired branch.
     */
    @ArchTest
    static final ArchRule serviceDependsOnStrategyInterfacesOnly =
            noClasses()
                    .that()
                    .resideInAPackage("..service..")
                    .should()
                    .dependOnClassesThat(
                            resideInAPackage("..strategy..")
                                    .and(not(INTERFACES))
                                    .as("concrete Strategy implementations"))
                    .because(
                            "AD-8: services depend on Strategy interfaces, never on implementations")
                    .allowEmptyShould(true);

    /**
     * AD-8: {@code controller} is the outermost layer. Nothing may depend on it -- a dependency
     * pointing back at a controller is the dependency direction inverting.
     */
    @ArchTest
    static final ArchRule nothingDependsOnController =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("..controller..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..controller..")
                    .because("AD-8: nothing imports controller")
                    .allowEmptyShould(true);

    /**
     * AD-7: the domain package is named {@code model}. {@code entity} implies an ORM, and there is
     * none.
     */
    @ArchTest
    static final ArchRule noPackageIsNamedEntity =
            noClasses()
                    .should()
                    .resideInAPackage("..entity..")
                    .because(
                            "AD-7: the domain package is model, never entity -- there is no ORM in this system")
                    .allowEmptyShould(true);

    /**
     * AD-9: {@code shared} sits at the bottom of the feature order, so it may depend on no feature.
     *
     * <p>This is the rule that stops {@code shared} becoming a dumping ground. The membership test
     * for {@code shared} is whether a type encodes a <em>convention</em> (Money's minor units,
     * Coordinates' precision and axis order) rather than domain behaviour; "two features happen to
     * use it" is not a reason to promote anything into it. That test is a judgement, so it is
     * guarded by a rule instead of by discipline.
     */
    @ArchTest
    static final ArchRule sharedDependsOnNoFeaturePackage =
            noClasses()
                    .that()
                    .resideInAPackage(MATCHING + "shared..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            MATCHING + "fare..",
                            MATCHING + "ride..",
                            MATCHING + "dispatch..",
                            MATCHING + "quote..")
                    .because(
                            "AD-9: shared is the bottom of shared <- fare <- ride <- dispatch <- quote")
                    .allowEmptyShould(true);

    /** AD-9: the feature packages of matching-service form a one-way order. */
    @ArchTest
    static final ArchRule featureDependenciesRunOneWay =
            Architectures.layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("shared")
                    .definedBy(MATCHING + "shared..")
                    .layer("fare")
                    .definedBy(MATCHING + "fare..")
                    .layer("ride")
                    .definedBy(MATCHING + "ride..")
                    .layer("dispatch")
                    .definedBy(MATCHING + "dispatch..")
                    .layer("quote")
                    .definedBy(MATCHING + "quote..")
                    .whereLayer("quote")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("dispatch")
                    .mayOnlyBeAccessedByLayers("quote")
                    .whereLayer("ride")
                    .mayOnlyBeAccessedByLayers("dispatch", "quote")
                    .whereLayer("fare")
                    .mayOnlyBeAccessedByLayers("ride", "dispatch", "quote")
                    .withOptionalLayers(true)
                    .because("AD-9: shared <- fare <- ride <- dispatch <- quote");
}
