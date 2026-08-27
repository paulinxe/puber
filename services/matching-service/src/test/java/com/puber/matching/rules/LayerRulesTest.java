package com.puber.matching.rules;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.matching.rules.fixtures.DependsOnAController;
import com.puber.matching.rules.fixtures.controller.AControllerNothingMayDependOn;
import com.puber.matching.rules.fixtures.entity.AClassInAPackageNamedEntity;
import com.puber.matching.rules.fixtures.model.ModelTypeCarryingAFrameworkAnnotation;
import com.puber.matching.rules.fixtures.model.ModelTypeThatDependsOnJackson;
import com.puber.matching.rules.fixtures.model.ModelTypeThatHoldsAWireMessage;
import com.puber.matching.rules.fixtures.service.ServiceThatDependsOnAConcreteStrategy;
import com.puber.matching.shared.model.Deadline;
import com.puber.matching.shared.model.Distance;
import com.puber.matching.shared.model.Money;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof that the four layer rules can actually fail.
 *
 * <p>All four were written in Story 1.1 over packages that did not exist, so none had ever
 * evaluated a class. {@code fare} is the first code any of them governs.
 */
class LayerRulesTest {

    @Test
    @DisplayName("AC5: a service holding a concrete Strategy implementation is rejected")
    void rejects_a_service_that_depends_on_an_implementation() {
        assertRejects(
                ArchitectureRulesTest.serviceDependsOnStrategyInterfacesOnly,
                ServiceThatDependsOnAConcreteStrategy.class,
                "a service was allowed to name a concrete Strategy, which turns the Strategy back"
                        + " into a hard-wired branch");
    }

    @Test
    @DisplayName("AC5: depending on a controller is rejected -- nothing imports the outer layer")
    void rejects_a_dependency_pointing_back_at_a_controller() {
        JavaClasses imported =
                new ClassFileImporter()
                        .importClasses(
                                DependsOnAController.class, AControllerNothingMayDependOn.class);

        AssertionError rejection =
                assertThrows(
                        AssertionError.class,
                        () -> ArchitectureRulesTest.nothingDependsOnController.check(imported),
                        "the dependency direction was allowed to invert");

        assertTrue(
                rejection.getMessage().contains("DependsOnAController"),
                () ->
                        "the violation was not attributed to the violator: "
                                + rejection.getMessage());
    }

    @Test
    @DisplayName("AC5: a package named entity is rejected -- there is no ORM here")
    void rejects_a_package_named_entity() {
        assertRejects(
                ArchitectureRulesTest.noPackageIsNamedEntity,
                AClassInAPackageNamedEntity.class,
                "a package named entity was accepted, and the name implies an ORM this system does"
                        + " not have");
    }

    @Test
    @DisplayName("AC5: a model type depending on Jackson 3 is rejected -- the old list missed it")
    void rejects_a_model_type_that_depends_on_the_framework() {
        assertRejects(
                ArchitectureRulesTest.modelDependsOnNothingFrameworkFlavoured,
                ModelTypeThatDependsOnJackson.class,
                "the domain model was allowed to depend on Jackson 3, which is exactly the gap the"
                        + " enumerated blocklist left open");
    }

    @Test
    @DisplayName("AD-8: a model type annotated by the framework is rejected")
    void rejects_a_model_type_carrying_a_framework_annotation() {
        assertRejects(
                ArchitectureRulesTest.modelDependsOnNothingFrameworkFlavoured,
                ModelTypeCarryingAFrameworkAnnotation.class,
                "an annotation was allowed to carry the framework into the domain model, which is"
                        + " the form the leak actually takes");
    }

    @Test
    @DisplayName("AD-8: a model type holding a generated contract message is rejected")
    void rejects_a_model_type_that_holds_a_wire_message() {
        assertRejects(
                ArchitectureRulesTest.modelDependsOnNothingFrameworkFlavoured,
                ModelTypeThatHoldsAWireMessage.class,
                "the domain model was allowed to hold a generated protobuf message -- the rule's"
                        + " com.puber.. allowance readmits com.puber.contracts.. unless it is"
                        + " subtracted, and no production type violates it, so nothing else is red");
    }

    @Test
    @DisplayName("AC5: the real domain types depend on the JDK alone, and are accepted")
    void accepts_the_real_domain_types() {
        JavaClasses domain =
                new ClassFileImporter().importClasses(Money.class, Distance.class, Deadline.class);

        ArchitectureRulesTest.modelDependsOnNothingFrameworkFlavoured.check(domain);
    }

    private static void assertRejects(ArchRule rule, Class<?> fixture, String whatWentUnnoticed) {
        JavaClasses imported = new ClassFileImporter().importClasses(fixture);

        AssertionError rejection =
                assertThrows(AssertionError.class, () -> rule.check(imported), whatWentUnnoticed);

        assertTrue(
                rejection.getMessage().contains(fixture.getSimpleName()),
                () ->
                        "the violation was not attributed to the violator: "
                                + rejection.getMessage());
    }
}
