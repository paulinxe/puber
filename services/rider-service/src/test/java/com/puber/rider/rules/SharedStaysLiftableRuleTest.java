package com.puber.rider.rules;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.rider.model.Quote;
import com.puber.rider.shared.InvalidRequestException;
import com.puber.rider.shared.SharedTypeThatDependsOnThisService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof that {@code sharedDependsOnNothingElseInThisService} can actually fail.
 *
 * <p>This is also its non-vacuity check: the rule names {@code com.puber.rider.shared..}
 * absolutely, so a typo there would leave it scanning an empty set and passing forever. It passing
 * is only meaningful because this test shows it rejecting a real class in that package.
 */
class SharedStaysLiftableRuleTest {

    @Test
    @DisplayName("AC11: a shared type naming something only this service has is rejected")
    void rejects_shared_depending_on_the_rest_of_this_service() {
        JavaClasses imported =
                new ClassFileImporter()
                        .importClasses(SharedTypeThatDependsOnThisService.class, Quote.class);

        AssertionError rejection =
                assertThrows(
                        AssertionError.class,
                        () ->
                                ArchitectureRulesTest.sharedDependsOnNothingElseInThisService.check(
                                        imported),
                        "shared was allowed to name this service's own layers, so the directory is"
                                + " no longer liftable into the next service and nobody finds out"
                                + " until they try");

        assertTrue(
                rejection.getMessage().contains("SharedTypeThatDependsOnThisService"),
                () ->
                        "the violation was not attributed to the violator: "
                                + rejection.getMessage());
    }

    @Test
    @DisplayName("AC11: a shared type naming only shared is accepted")
    void accepts_shared_that_depends_on_nothing_else() {
        JavaClasses shared = new ClassFileImporter().importClasses(InvalidRequestException.class);

        ArchitectureRulesTest.sharedDependsOnNothingElseInThisService.check(shared);
    }
}
