package com.puber.matching.rules;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.matching.config.ClockConfiguration;
import com.puber.matching.fare.model.FareRule;
import com.puber.matching.shared.SharedTypeThatDependsOnConfiguration;
import com.puber.matching.shared.model.Money;
import com.puber.matching.shared.model.SharedTypeThatDependsOnAFeature;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof that {@code sharedDependsOnNothingElseInThisService} can actually fail, and that it catches
 * something {@code sharedDependsOnNoFeaturePackage} does not.
 *
 * <p>Also its non-vacuity check: the rule names {@code com.puber.matching.shared..} absolutely, so
 * a typo there would leave it scanning an empty set and passing forever.
 */
class SharedStaysLiftableRuleTest {

    @Test
    @DisplayName("AC11: a shared type naming this service's own wiring is rejected")
    void rejects_shared_depending_on_a_package_the_feature_clause_never_looked_at() {
        JavaClasses imported =
                new ClassFileImporter()
                        .importClasses(
                                SharedTypeThatDependsOnConfiguration.class,
                                ClockConfiguration.class);

        AssertionError rejection =
                assertThrows(
                        AssertionError.class,
                        () ->
                                ArchitectureRulesTest.sharedDependsOnNothingElseInThisService.check(
                                        imported),
                        "shared was allowed to name config, which is not a feature and so was never"
                                + " covered by the feature clause");

        assertTrue(
                rejection.getMessage().contains("SharedTypeThatDependsOnConfiguration"),
                () ->
                        "the violation was not attributed to the violator: "
                                + rejection.getMessage());
    }

    @Test
    @DisplayName("AC11: the feature clause alone accepts what the newer clause rejects")
    void the_feature_clause_does_not_already_cover_it() {
        JavaClasses imported =
                new ClassFileImporter()
                        .importClasses(
                                SharedTypeThatDependsOnConfiguration.class,
                                ClockConfiguration.class);

        // Green on purpose: config is no feature. Replacing the feature clause with the newer one
        // would lose AD-9's order, and dropping this assertion would let someone believe the two
        // rules are the same rule written twice.
        ArchitectureRulesTest.sharedDependsOnNoFeaturePackage.check(imported);
    }

    @Test
    @DisplayName("AC11: a shared type reaching into a feature is rejected by the newer clause too")
    void rejects_shared_depending_on_a_feature() {
        JavaClasses imported =
                new ClassFileImporter()
                        .importClasses(SharedTypeThatDependsOnAFeature.class, FareRule.class);

        assertThrows(
                AssertionError.class,
                () -> ArchitectureRulesTest.sharedDependsOnNothingElseInThisService.check(imported),
                "the newer clause missed a feature dependency, so it is not strictly stronger than"
                        + " the one it sits beside");
    }

    @Test
    @DisplayName("AC11: a shared type naming only shared is accepted")
    void accepts_shared_that_depends_on_nothing_else() {
        JavaClasses shared = new ClassFileImporter().importClasses(Money.class);

        ArchitectureRulesTest.sharedDependsOnNothingElseInThisService.check(shared);
    }
}
