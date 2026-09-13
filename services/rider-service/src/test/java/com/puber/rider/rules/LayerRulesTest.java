package com.puber.rider.rules;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.rider.model.Quote;
import com.puber.rider.rules.fixtures.DependsOnAController;
import com.puber.rider.rules.fixtures.DependsOnADto;
import com.puber.rider.rules.fixtures.controller.AControllerNothingMayDependOn;
import com.puber.rider.rules.fixtures.dto.AWireShapeNestedInsideAnother;
import com.puber.rider.rules.fixtures.dto.AWireShapeOnlyAControllerMaySee;
import com.puber.rider.rules.fixtures.entity.AClassInAPackageNamedEntity;
import com.puber.rider.rules.fixtures.model.ModelTypeCarryingAFrameworkAnnotation;
import com.puber.rider.rules.fixtures.model.ModelTypeThatDependsOnJackson;
import com.puber.rider.rules.fixtures.model.ModelTypeThatHoldsAWireMessage;
import com.puber.rider.rules.fixtures.service.ServiceThatDependsOnAConcreteStrategy;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof that the layer rules can actually fail in <em>this</em> service.
 *
 * <p>AC11's second clause: a copied rule that has never evaluated a class in its new home is
 * indistinguishable from a rule that cannot fire there.
 */
class LayerRulesTest {

    @Test
    @DisplayName("AC11: a service holding a concrete Strategy implementation is rejected")
    void rejects_a_service_that_depends_on_an_implementation() {
        assertRejects(
                ArchitectureRulesTest.serviceDependsOnStrategyInterfacesOnly,
                ServiceThatDependsOnAConcreteStrategy.class,
                "a service was allowed to name a concrete Strategy, which turns the Strategy back"
                        + " into a hard-wired branch");
    }

    @Test
    @DisplayName("AC11: depending on a controller is rejected -- nothing imports the outer layer")
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
    @DisplayName("AC11: a class outside controller depending on a wire DTO is rejected")
    void rejects_a_dependency_on_a_dto_from_outside_the_controller() {
        JavaClasses imported =
                new ClassFileImporter()
                        .importClasses(DependsOnADto.class, AWireShapeOnlyAControllerMaySee.class);

        AssertionError rejection =
                assertThrows(
                        AssertionError.class,
                        () -> ArchitectureRulesTest.onlyControllerDependsOnDto.check(imported),
                        "the wire shape was allowed past the controller, so a rename in the JSON"
                                + " now reaches into the service layer");

        assertTrue(
                rejection.getMessage().contains("DependsOnADto"),
                () ->
                        "the violation was not attributed to the violator: "
                                + rejection.getMessage());
    }

    @Test
    @DisplayName("AC11: a DTO naming another DTO is accepted -- QuoteRequest nests Coordinates")
    void accepts_a_dto_that_depends_on_another_dto() {
        JavaClasses wireShapes =
                new ClassFileImporter()
                        .importClasses(
                                AWireShapeNestedInsideAnother.class,
                                AWireShapeOnlyAControllerMaySee.class);

        ArchitectureRulesTest.onlyControllerDependsOnDto.check(wireShapes);
    }

    @Test
    @DisplayName("AC11: a package named entity is rejected -- there is no ORM here")
    void rejects_a_package_named_entity() {
        assertRejects(
                ArchitectureRulesTest.noPackageIsNamedEntity,
                AClassInAPackageNamedEntity.class,
                "a package named entity was accepted, and the name implies an ORM this system does"
                        + " not have");
    }

    @Test
    @DisplayName("AC11: a model type depending on Jackson 3 is rejected")
    void rejects_a_model_type_that_depends_on_the_framework() {
        assertRejects(
                ArchitectureRulesTest.modelDependsOnNothingFrameworkFlavoured,
                ModelTypeThatDependsOnJackson.class,
                "the domain model was allowed to depend on Jackson 3, which is exactly the gap the"
                        + " enumerated blocklist left open");
    }

    @Test
    @DisplayName("AC11: a model type annotated by the framework is rejected")
    void rejects_a_model_type_carrying_a_framework_annotation() {
        assertRejects(
                ArchitectureRulesTest.modelDependsOnNothingFrameworkFlavoured,
                ModelTypeCarryingAFrameworkAnnotation.class,
                "an annotation was allowed to carry the framework into the domain model, which is"
                        + " the form the leak actually takes");
    }

    @Test
    @DisplayName("AC11: a model type holding a generated contract message is rejected")
    void rejects_a_model_type_that_holds_a_wire_message() {
        assertRejects(
                ArchitectureRulesTest.modelDependsOnNothingFrameworkFlavoured,
                ModelTypeThatHoldsAWireMessage.class,
                "the domain model was allowed to hold a generated protobuf message -- this service"
                        + " generates the same stubs as matching-service, so it has the same hole,"
                        + " and no production type violates it so nothing else is red");
    }

    @Test
    @DisplayName("AC11: the real domain type depends on the JDK alone, and is accepted")
    void accepts_the_real_domain_type() {
        JavaClasses domain = new ClassFileImporter().importClasses(Quote.class);

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
