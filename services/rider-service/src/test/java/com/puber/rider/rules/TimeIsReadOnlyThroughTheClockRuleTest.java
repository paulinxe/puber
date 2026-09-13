package com.puber.rider.rules;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.rider.rules.fixtures.ConvertsTimeWithoutReadingIt;
import com.puber.rider.rules.fixtures.ReadsTimeDirectly;
import com.puber.rider.rules.fixtures.UsesTheLegacyDateApi;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof that the two time rules can actually fail here.
 *
 * <p>This service has no clock, so the copy carries no exemption and there is no exemption test to
 * go with it. That is the whole difference from matching-service's version.
 */
class TimeIsReadOnlyThroughTheClockRuleTest {

    @Test
    @DisplayName("AC11: every direct time read in the fixture is rejected, so the rule can fail")
    void rejects_every_direct_time_read() {
        assertEveryDeclaredMethodIsReported(
                ArchitectureRulesTest.timeIsReadOnlyThroughTheClock, ReadsTimeDirectly.class);
    }

    @Test
    @DisplayName("AC11: every use of the legacy date API is rejected, conversions included")
    void rejects_every_use_of_the_legacy_date_api() {
        assertEveryDeclaredMethodIsReported(
                ArchitectureRulesTest.theLegacyDateApiIsNotUsedAtAll, UsesTheLegacyDateApi.class);
    }

    @Test
    @DisplayName("AC11: the rule accepts converting a reading it did not take itself")
    void accepts_conversion_and_arithmetic() {
        JavaClasses converter =
                new ClassFileImporter().importClasses(ConvertsTimeWithoutReadingIt.class);

        ArchitectureRulesTest.timeIsReadOnlyThroughTheClock.check(converter);
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
}
