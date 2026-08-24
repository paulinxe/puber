package com.puber.matching.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.matching.rules.fixtures.BuildsABigDecimalFromADouble;
import com.puber.matching.rules.fixtures.BuildsABigDecimalSafely;
import com.puber.matching.rules.fixtures.DeclaresFloatingPoint;
import com.puber.matching.shared.model.Distance;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proof that the two money rules can actually fail. */
class MoneyIsNeverFloatingPointRuleTest {

    @Test
    @DisplayName(
            "AC5: every floating-point member of the fixture is reported, so the rule can fail")
    void rejects_every_floating_point_declaration() {
        JavaClasses imported = new ClassFileImporter().importClasses(DeclaresFloatingPoint.class);

        AssertionError rejection =
                assertThrows(
                        AssertionError.class,
                        () ->
                                ArchitectureRulesTest.floatingPointIsConfinedToDistance.check(
                                        imported),
                        "the rule accepted DeclaresFloatingPoint, which exists only to be rejected"
                                + " -- it cannot fail, so it enforces nothing");

        // The fixture is the list. Reflecting over it means there is no second list to fall out of
        // step: a member added there that the rule misses fails here.
        Field[] fields = DeclaresFloatingPoint.class.getDeclaredFields();
        Method[] methods = DeclaresFloatingPoint.class.getDeclaredMethods();
        assertTrue(fields.length > 0 && methods.length > 0, "the fixture declares nothing");
        for (Field field : fields) {
            assertTrue(
                    rejection.getMessage().contains("." + field.getName()),
                    () ->
                            "the field "
                                    + field.getName()
                                    + " was not reported, so the rule does not cover fields: "
                                    + rejection.getMessage());
        }
        for (Method method : methods) {
            assertTrue(
                    rejection.getMessage().contains("." + method.getName() + "("),
                    () ->
                            "the method "
                                    + method.getName()
                                    + " was not reported, so the rule does not cover it: "
                                    + rejection.getMessage());
        }
    }

    @Test
    @DisplayName("AC5: Distance is exempt, and nothing beside it is")
    void exempts_the_one_type_allowed_to_hold_a_double() {
        // Both at once, deliberately: Distance alone leaves the rule nothing to check, which
        // proves nothing about the exemption.
        JavaClasses distanceAndViolator =
                new ClassFileImporter().importClasses(Distance.class, DeclaresFloatingPoint.class);

        AssertionError rejection =
                assertThrows(
                        AssertionError.class,
                        () ->
                                ArchitectureRulesTest.floatingPointIsConfinedToDistance.check(
                                        distanceAndViolator),
                        "the violator went unreported once Distance was imported alongside it");

        assertTrue(
                rejection.getMessage().contains("DeclaresFloatingPoint"),
                () ->
                        "the violation was not attributed to the violator: "
                                + rejection.getMessage());
        assertFalse(
                rejection.getMessage().contains("Distance."),
                () ->
                        "Distance was reported -- the one type allowed to hold a double cannot be: "
                                + rejection.getMessage());
    }

    @Test
    @DisplayName("AC5: both BigDecimal(double) constructors are rejected")
    void rejects_a_big_decimal_built_from_a_double() {
        JavaClasses imported =
                new ClassFileImporter().importClasses(BuildsABigDecimalFromADouble.class);

        AssertionError rejection =
                assertThrows(
                        AssertionError.class,
                        () ->
                                ArchitectureRulesTest.bigDecimalIsNeverBuiltFromADouble.check(
                                        imported),
                        "new BigDecimal(0.1) was accepted, so the clause that the whole Money"
                                + " convention was amended for enforces nothing");

        for (Method method : BuildsABigDecimalFromADouble.class.getDeclaredMethods()) {
            assertTrue(
                    rejection.getMessage().contains("." + method.getName() + "("),
                    () ->
                            method.getName()
                                    + " was not reported, so one of the two constructors is"
                                    + " unguarded: "
                                    + rejection.getMessage());
        }
    }

    @Test
    @DisplayName("AC5: BigDecimal.valueOf(double) is accepted -- it is the correct call")
    void accepts_the_safe_way_to_build_a_big_decimal() {
        JavaClasses imported = new ClassFileImporter().importClasses(BuildsABigDecimalSafely.class);

        ArchitectureRulesTest.bigDecimalIsNeverBuiltFromADouble.check(imported);
    }
}
