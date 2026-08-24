package com.puber.matching.rules;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.matching.fare.model.FareRule;
import com.puber.matching.shared.model.Money;
import com.puber.matching.shared.model.SharedTypeThatDependsOnAFeature;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof that the feature order can actually fail.
 *
 * <p>These two rules were written in Story 1.1 and had never evaluated a class: {@code fare} is the
 * first feature package to exist, so until now there was nothing for either of them to catch.
 */
class FeaturePackagesRunOneWayRuleTest {

    @Test
    @DisplayName("AC5: shared reaching up into a feature is rejected")
    void rejects_shared_depending_on_a_feature() {
        JavaClasses imported =
                new ClassFileImporter()
                        .importClasses(SharedTypeThatDependsOnAFeature.class, FareRule.class);

        AssertionError rejection =
                assertThrows(
                        AssertionError.class,
                        () -> ArchitectureRulesTest.sharedDependsOnNoFeaturePackage.check(imported),
                        "shared was allowed to depend on fare, so nothing stops shared becoming a"
                                + " dumping ground");

        assertTrue(
                rejection.getMessage().contains("SharedTypeThatDependsOnAFeature"),
                () ->
                        "the violation was not attributed to the violator: "
                                + rejection.getMessage());
    }

    @Test
    @DisplayName("AC5: shared depending on nothing but itself is accepted")
    void accepts_shared_that_depends_on_no_feature() {
        JavaClasses shared = new ClassFileImporter().importClasses(Money.class);

        ArchitectureRulesTest.sharedDependsOnNoFeaturePackage.check(shared);
    }

    @Test
    @DisplayName("AC5: the layered rule rejects the same violation, shared reaching up into fare")
    void rejects_a_lower_layer_depending_on_a_higher_one() {
        // One fixture, two rules: this is the layered rule, the test above is the shared one. The
        // fare-into-ride edge needs no fixture of its own -- ride arrives in Epic 3 with real
        // classes, and a stub package would exist only to be deleted then.
        JavaClasses imported =
                new ClassFileImporter()
                        .importClasses(SharedTypeThatDependsOnAFeature.class, FareRule.class);

        AssertionError rejection =
                assertThrows(
                        AssertionError.class,
                        () -> ArchitectureRulesTest.featureDependenciesRunOneWay.check(imported),
                        "a lower layer was allowed to depend on a higher one, so the one-way order"
                                + " is not enforced");

        assertTrue(
                rejection.getMessage().contains("SharedTypeThatDependsOnAFeature"),
                () ->
                        "the violation was not attributed to the violator: "
                                + rejection.getMessage());
    }

    @Test
    @DisplayName("AC5: the real fare package depends only on shared, and is accepted")
    void accepts_the_real_feature_packages() {
        // Test sources excluded, exactly as ArchitectureRulesTest excludes them -- otherwise this
        // imports the deliberate violator above and fails for the wrong reason.
        JavaClasses production =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.puber.matching.fare");

        ArchitectureRulesTest.featureDependenciesRunOneWay.check(production);
    }
}
