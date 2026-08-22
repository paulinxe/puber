package com.puber.matching.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.matching.rules.fixtures.ConvertsTimeWithoutReadingIt;
import com.puber.matching.rules.fixtures.ReadsTimeDirectly;
import com.puber.matching.rules.fixtures.UsesTheLegacyDateApi;
import com.puber.matching.shared.strategy.NeighbourStrategyThatReadsTimeDirectly;
import com.puber.matching.shared.strategy.SystemClock;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proof that the two time rules can actually fail. */
class TimeIsReadOnlyThroughTheClockRuleTest {

    @Test
    @DisplayName("AC1: every direct time read in the fixture is rejected, so the rule can fail")
    void rejectsEveryDirectTimeRead() {
        assertEveryDeclaredMethodIsReported(
                ArchitectureRulesTest.timeIsReadOnlyThroughTheClock, ReadsTimeDirectly.class);
    }

    @Test
    @DisplayName("AC1: every use of the legacy date API is rejected, conversions included")
    void rejectsEveryUseOfTheLegacyDateApi() {
        assertEveryDeclaredMethodIsReported(
                ArchitectureRulesTest.theLegacyDateApiIsNotUsedAtAll, UsesTheLegacyDateApi.class);
    }

    /**
     * The fixture is the list, and this asserts every entry in it. There is no second list to fall
     * out of step.
     */
    private static void assertEveryDeclaredMethodIsReported(ArchRule rule, Class<?> fixture) {
        JavaClasses imported = new ClassFileImporter().importClasses(fixture);

        AssertionError rejection =
                assertThrows(
                        AssertionError.class,
                        () -> rule.check(imported),
                        () ->
                                "the rule accepted "
                                        + fixture.getSimpleName()
                                        + ", which exists only to be rejected -- it cannot fail, so"
                                        + " it enforces nothing");

        Method[] declared = fixture.getDeclaredMethods();
        assertTrue(declared.length > 0, "the fixture declares nothing, so this proves nothing");
        for (Method method : declared) {
            assertTrue(
                    rejection.getMessage().contains("." + method.getName() + "()"),
                    () ->
                            fixture.getSimpleName()
                                    + "."
                                    + method.getName()
                                    + "() was not reported, so the rule does not cover it: "
                                    + rejection.getMessage());
        }
    }

    @Test
    @DisplayName("AC1: the rule accepts converting a reading it did not take itself")
    void acceptsConversionAndArithmetic() {
        JavaClasses converter =
                new ClassFileImporter().importClasses(ConvertsTimeWithoutReadingIt.class);

        ArchitectureRulesTest.timeIsReadOnlyThroughTheClock.check(converter);
    }

    @Test
    @DisplayName("AC1: SystemClock is exempt, and nothing beside it is")
    void exemptsTheOneClassAllowedToReadTime() {
        // Both at once, deliberately: SystemClock alone gives the rule nothing to check, which
        // ArchUnit rejects and which proves nothing about the exemption.
        JavaClasses clockAndViolator =
                new ClassFileImporter().importClasses(SystemClock.class, ReadsTimeDirectly.class);

        AssertionError rejection =
                assertThrows(
                        AssertionError.class,
                        () ->
                                ArchitectureRulesTest.timeIsReadOnlyThroughTheClock.check(
                                        clockAndViolator),
                        "the violator went unreported once SystemClock was imported alongside it");

        assertTrue(
                rejection.getMessage().contains("ReadsTimeDirectly.java"),
                () ->
                        "the violation was not attributed to the violator: "
                                + rejection.getMessage());
        assertFalse(
                rejection.getMessage().contains("SystemClock.java"),
                () ->
                        "SystemClock was reported as a violation -- the one class allowed to read a"
                                + " system time source cannot be: "
                                + rejection.getMessage());
    }

    @Test
    @DisplayName(
            "AC1: the exemption is by class -- a neighbour in the same package inherits nothing")
    void doesNotExemptTheRestOfSystemClocksPackage() {
        JavaClasses neighbour =
                new ClassFileImporter().importClasses(NeighbourStrategyThatReadsTimeDirectly.class);

        assertThrows(
                AssertionError.class,
                () -> ArchitectureRulesTest.timeIsReadOnlyThroughTheClock.check(neighbour),
                "the exemption covers all of shared.strategy, so every strategy added there may"
                        + " read time directly");
    }
}
