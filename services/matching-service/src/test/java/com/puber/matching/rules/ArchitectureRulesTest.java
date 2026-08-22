package com.puber.matching.rules;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.puber.matching.shared.strategy.SystemClock;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

/**
 * The structural rules of this service, asserted as ordinary tests.
 *
 * <p>Several rules are still vacuously true: {@code config} and {@code shared} arrived with Story
 * 1.2, and the feature packages arrive with the stories that first need them. They are written
 * before the code they govern on purpose. These are the rules four more services inherit, and a
 * rule added after the code it governs is a rule that gets negotiated against existing violations
 * rather than enforced. {@code allowEmptyShould(true)} is therefore deliberate, not a workaround.
 */
@AnalyzeClasses(
        packages = "com.puber.matching",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    private static final String MATCHING = "com.puber.matching.";

    /**
     * Exact types, not subtypes, on purpose: {@code java.sql.Timestamp} extends {@code
     * java.util.Date}, and a JDBC driver returning one is fine.
     */
    static final List<Class<?>> LEGACY_DATE_API =
            List.of(Date.class, Calendar.class, GregorianCalendar.class, TimeZone.class);

    private static final DescribedPredicate<JavaAccess<?>> A_SYSTEM_TIME_SOURCE =
            DescribedPredicate.describe("a system time source", ArchitectureRulesTest::readsTime);

    /** NFR-9, AD-58: every reading of the current time comes from the {@code Clock} strategy. */
    @ArchTest
    static final ArchRule timeIsReadOnlyThroughTheClock =
            noClasses()
                    .that()
                    .doNotBelongToAnyOf(SystemClock.class)
                    .should()
                    .accessTargetWhere(A_SYSTEM_TIME_SOURCE)
                    .because(
                            "NFR-9: time is read only through the Clock strategy, so durations are"
                                    + " monotonic and every window is testable without waiting");

    /**
     * The rule above exempts {@code SystemClock}, so a class building its own would read the clock
     * and pass. No test can advance a clock it was never given. {@code config} is excepted because
     * that is where the bean is declared.
     */
    @ArchTest
    static final ArchRule theRealClockIsOnlyEverInjected =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(MATCHING + "config..")
                    .should()
                    .dependOnClassesThat()
                    .areAssignableTo(SystemClock.class)
                    .because(
                            "NFR-9: the real clock is wired once in config and injected everywhere"
                                    + " else -- a class that constructs its own reads a clock no"
                                    + " test can advance");

    /**
     * Listing the clock-reading methods of {@code Date} and {@code Calendar} one by one is what let
     * {@code new GregorianCalendar()} through: a constructor belongs to the subclass, so it never
     * matched {@code Calendar}. Banning the types leaves nothing to miss.
     */
    @ArchTest
    static final ArchRule theLegacyDateApiIsNotUsedAtAll =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .belongToAnyOf(LEGACY_DATE_API.toArray(new Class<?>[0]))
                    .because(
                            "Timestamps convention: Instant in, Instant out, TIMESTAMPTZ at rest --"
                                    + " java.util.Date and Calendar are mutable and zone-dependent,"
                                    + " and every clock read they offer is one more to enumerate")
                    .allowEmptyShould(true);

    /**
     * Does this call read the clock? Asked once for every method call in the service; a yes fails
     * the build.
     *
     * <p>{@code owner} is the class being called and {@code member} the method name, so {@code
     * Instant.now()} arrives here as {@code "java.time.Instant"} and {@code "now"}.
     */
    private static boolean readsTime(JavaAccess<?> access) {
        String owner = access.getTargetOwner().getFullName();
        String member = access.getTarget().getName();
        return switch (owner) {
            case "java.lang.System" ->
                    member.equals("currentTimeMillis") || member.equals("nanoTime");
            // Clock.fixed and Clock.offset are allowed: they read nothing.
            case "java.time.Clock" -> member.startsWith("system") || member.startsWith("tick");
            // Not the time, but banned with it. See project-context.md, Timestamps.
            case "java.time.ZoneId" -> member.equals("systemDefault");
            default -> owner.startsWith("java.time.") && member.equals("now");
        };
    }

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
