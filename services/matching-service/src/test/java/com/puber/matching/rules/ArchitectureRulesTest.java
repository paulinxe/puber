package com.puber.matching.rules;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.puber.matching.shared.model.Distance;
import com.puber.matching.shared.strategy.SystemClock;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.ConstructorCallTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.Architectures;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;
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

    private static final Set<String> FLOATING_POINT_TYPE_NAMES =
            Set.of("float", "double", "java.lang.Float", "java.lang.Double");

    private static final DescribedPredicate<JavaClass> FLOATING_POINT =
            DescribedPredicate.describe(
                    "a floating-point type",
                    type -> FLOATING_POINT_TYPE_NAMES.contains(componentOf(type).getName()));

    private static final DescribedPredicate<JavaAccess<?>> A_BIG_DECIMAL_BUILT_FROM_A_DOUBLE =
            DescribedPredicate.describe(
                    "a BigDecimal constructed from a double",
                    ArchitectureRulesTest::buildsABigDecimalFromADouble);

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
     * AD-8: the domain model is plain Java. Anything framework-flavoured reaching {@code model}
     * means persistence or transport concerns have leaked into the domain.
     *
     * <p>Expressed as what the model <em>may</em> depend on, not as a list of what it may not. The
     * list form named {@code com.fasterxml.jackson..} -- Jackson 2, which is not on the classpath
     * at all -- and did not name {@code tools.jackson..}, the Jackson 3 that is; so the one
     * framework it called out for the domain was the one it could never meet. Every framework, and
     * every framework added later, fails this form without anybody remembering to add it.
     */
    @ArchTest
    static final ArchRule modelDependsOnNothingFrameworkFlavoured =
            classes()
                    .that()
                    .resideInAPackage("..model..")
                    .should()
                    .onlyDependOnClassesThat(
                            resideInAnyPackage("java..", "com.puber..")
                                    .as("the JDK or this project"))
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

    /**
     * AC5: money is integer minor units, so no production type declares floating point. {@code
     * Distance} is the single exemption, derived from the type rather than from a list: it is the
     * one type that stores the trigonometric result, and every arithmetic caller takes a {@code
     * BigDecimal} back out of it.
     *
     * <p><strong>Declarations only -- fields, return types and parameters, including array
     * components and generic arguments.</strong> Method bodies are not read, so {@code
     * Coordinates.distanceTo} computes the haversine in {@code double} locals and passes. That is
     * intended (the trigonometry has to happen somewhere) but it is a limit, not a clean bill: a
     * class doing its money arithmetic in locals would pass too. Proven by planting a local-only
     * method and watching the rule stay green.
     *
     * <p>Nothing enforced this before. The old Money convention said "DECIMAL at rest", which a
     * {@code double} field satisfies right up to the moment it is stored.
     */
    @ArchTest
    static final ArchRule floatingPointIsConfinedToDistance =
            classes()
                    .that()
                    .doNotBelongToAnyOf(Distance.class)
                    .should(neverDeclareOrReturnFloatingPoint())
                    .because(
                            "AC5: money is integer minor units -- a float or a double in a"
                                    + " declaration is where the rounding error gets in")
                    .allowEmptyShould(true);

    /**
     * The clause the old convention could not express. {@code new BigDecimal(0.1)} stores
     * 0.1000000000000000055511151231257827; {@code BigDecimal.valueOf(0.1)} stores 0.1. Both
     * compile and look identical in review, so the rule has to be mechanical.
     */
    @ArchTest
    static final ArchRule bigDecimalIsNeverBuiltFromADouble =
            noClasses()
                    .should()
                    .accessTargetWhere(A_BIG_DECIMAL_BUILT_FROM_A_DOUBLE)
                    .because(
                            "AC5: new BigDecimal(double) carries the binary rounding error into an"
                                    + " exact-decimal type -- BigDecimal.valueOf does not")
                    .allowEmptyShould(true);

    private static ArchCondition<JavaClass> neverDeclareOrReturnFloatingPoint() {
        return new ArchCondition<>("never declare or return a floating-point type") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                for (JavaField field : type.getFields()) {
                    reportIfFloatingPoint(events, field.getFullName(), field.getType());
                }
                for (JavaCodeUnit codeUnit : type.getCodeUnits()) {
                    reportIfFloatingPoint(events, codeUnit.getFullName(), codeUnit.getReturnType());
                    for (JavaType parameter : codeUnit.getParameterTypes()) {
                        reportIfFloatingPoint(events, codeUnit.getFullName(), parameter);
                    }
                }
            }
        };
    }

    /**
     * The generic type, not the erasure: a {@code List<Double>} field erases to {@code
     * java.util.List} and hides its argument, which is how a boxed collection of doubles used to
     * pass. Recursing over the arguments catches it, and {@code toErasure()} reduces a wildcard to
     * the bound it is written against.
     */
    private static void reportIfFloatingPoint(
            ConditionEvents events, String member, JavaType type) {
        JavaClass erasure = type.toErasure();
        if (FLOATING_POINT.test(erasure)) {
            events.add(
                    SimpleConditionEvent.violated(
                            erasure,
                            member
                                    + " uses the floating-point type "
                                    + componentOf(erasure).getName()));
        }
        if (type instanceof JavaParameterizedType parameterized) {
            for (JavaType argument : parameterized.getActualTypeArguments()) {
                reportIfFloatingPoint(events, member, argument);
            }
        }
    }

    /**
     * Only the two constructors that take a {@code double}. {@code BigDecimal.valueOf(double)} is a
     * method call, not a constructor, so it is untouched -- which it has to be, or the rule bans
     * the correct call and gets switched off.
     */
    private static boolean buildsABigDecimalFromADouble(JavaAccess<?> access) {
        if (!(access instanceof JavaConstructorCall call)) {
            return false;
        }
        ConstructorCallTarget target = call.getTarget();
        return target.getOwner().isEquivalentTo(BigDecimal.class)
                && target.getRawParameterTypes().stream()
                        .anyMatch(parameter -> parameter.isEquivalentTo(double.class));
    }

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
     * The element type of an array, however deeply nested; the type itself otherwise. An array does
     * not report its own name as {@code double[]} -- it reports the JVM form -- so matching on the
     * name alone missed every array of doubles.
     */
    private static JavaClass componentOf(JavaClass type) {
        JavaClass element = type;
        while (element.isArray()) {
            element = element.getComponentType();
        }
        return element;
    }
}
