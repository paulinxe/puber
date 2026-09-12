package com.puber.rider.rules;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
 * <p>Hand-copied from matching-service rather than shared: these are test code, and no service
 * depends on another's. Two rules are stronger here because this service has neither of the types
 * the matching-service versions exempt, and three are absent because they name things that do not
 * exist here -- {@code theRealClockIsOnlyEverInjected} names {@code SystemClock}, and the feature
 * rules are AD-9's, which scopes the feature split to matching-service. A rule that scans nothing
 * passes forever, so an inapplicable copy is worse than none.
 */
@AnalyzeClasses(packages = "com.puber.rider", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    private static final String RIDER = "com.puber.rider.";

    /**
     * The generated contract stubs. They live outside {@link #RIDER} on purpose -- they belong to
     * neither service -- so this scan does not import them; the name is here only to subtract them
     * from what {@code model} may depend on.
     */
    private static final String CONTRACTS = "com.puber.contracts..";

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

    /**
     * NFR-9, AD-58: every reading of the current time comes from the {@code Clock} strategy.
     *
     * <p>Stronger than matching-service's copy, which exempts {@code SystemClock}. This service has
     * no clock at all and reads no time, so there is nothing to exempt and <em>nothing</em> may
     * read the clock. The exemption comes back with the story that gives this service a clock, and
     * {@code theRealClockIsOnlyEverInjected} comes with it.
     */
    @ArchTest
    static final ArchRule timeIsReadOnlyThroughTheClock =
            noClasses()
                    .should()
                    .accessTargetWhere(A_SYSTEM_TIME_SOURCE)
                    .because(
                            "NFR-9: time is read only through the Clock strategy, and this service"
                                    + " has no clock -- so it reads no time at all");

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
     * <p>Expressed as what the model <em>may</em> depend on, not as a list of what it may not, so a
     * framework added later fails it without anybody remembering to add it. The generated contract
     * is subtracted from the {@code com.puber..} allowance, which would otherwise readmit it.
     */
    @ArchTest
    static final ArchRule modelDependsOnNothingFrameworkFlavoured =
            classes()
                    .that()
                    .resideInAPackage("..model..")
                    .should()
                    .onlyDependOnClassesThat(
                            resideInAnyPackage("java..", "com.puber..")
                                    .and(not(resideInAPackage(CONTRACTS)))
                                    .as("the JDK or this project, but never a generated contract"))
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
     * The wire DTOs sit in {@code dto} rather than inside {@code controller}, which cost them the
     * guard {@code nothingDependsOnController} was giving them for free. This is the replacement.
     *
     * <p>The {@code ..dto..} exclusion on the {@code that()} side is load-bearing: {@code
     * QuoteRequest} holds a nested {@code Coordinates} record, so DTOs depend on DTOs by design.
     * Without it the rule fails on correct code, and whoever hits that weakens the rule rather than
     * the code.
     */
    @ArchTest
    static final ArchRule onlyControllerDependsOnDto =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("..dto..")
                    .and()
                    .resideOutsideOfPackage("..controller..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..dto..")
                    .because(
                            "the wire shape reaches no further in than the controller that serves it")
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
     * {@code shared} holds the conventions every service's edge implements identically, so it has
     * to stay liftable into the next service: one directory to copy, one package line to change.
     *
     * <p>Stronger than matching-service's {@code sharedDependsOnNoFeaturePackage}, which forbids a
     * dependency on a <em>feature</em> -- this service has none. The moment {@code
     * ErrorDetailsHandler} names {@code Quote}, the directory stops being liftable and nobody finds
     * out until they try.
     */
    @ArchTest
    static final ArchRule sharedDependsOnNothingElseInThisService =
            noClasses()
                    .that()
                    .resideInAPackage(RIDER + "shared..")
                    .should()
                    .dependOnClassesThat(
                            resideInAPackage(RIDER + ".")
                                    .and(not(resideInAPackage(RIDER + "shared..")))
                                    .as("anything else in this service"))
                    .because(
                            "shared is copied per service, so it may name nothing that only this"
                                    + " service has")
                    .allowEmptyShould(true);

    /**
     * AC5: money is integer minor units, so no production type declares floating point.
     *
     * <p>Stronger than matching-service's {@code floatingPointIsConfinedToDistance}, which exempts
     * {@code Distance} -- the type that stores the trigonometric result. There is no such type
     * here: this service does no arithmetic at all, it passes decimal strings through. **That is
     * why the name differs**: a rule called "confined to Distance" in a service with no {@code
     * Distance} advertises an exemption that does not exist, and the two rules are not the same
     * rule anyway.
     *
     * <p><strong>Declarations only -- fields, return types and parameters, including array
     * components and generic arguments.</strong> Method bodies are not read, so a class doing its
     * arithmetic in locals would pass. That is a limit, not a clean bill.
     */
    @ArchTest
    static final ArchRule noProductionTypeDeclaresFloatingPoint =
            classes()
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
