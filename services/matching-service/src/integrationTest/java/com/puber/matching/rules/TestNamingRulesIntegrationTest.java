package com.puber.matching.rules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestNamingRulesIntegrationTest {

    private static final ArchRule testMethodsAreSnakeCase =
            noMethods()
                    .that()
                    .areAnnotatedWith(Test.class)
                    .should()
                    .haveNameMatching(".*[A-Z].*")
                    .because(
                            "AGENTS.md: test method names are snake_case -- prices_a_five_km_trip"
                                    + " reads, pricesAFiveKmTrip does not");

    @Test
    @DisplayName("AC7: every @Test method in the integration suite is snake_case")
    void integration_test_methods_are_snake_case() {
        JavaClasses suite = new ClassFileImporter().importPackages("com.puber.matching");

        // Without this the rule would pass by scanning nothing, which is the exact failure this
        // class was added to fix.
        List<JavaMethod> testMethods =
                suite.stream()
                        .flatMap(type -> type.getMethods().stream())
                        .filter(method -> method.isAnnotatedWith(Test.class))
                        .toList();
        assertTrue(
                testMethods.size() > 1,
                () ->
                        "the import found "
                                + testMethods.size()
                                + " @Test methods, so it read nothing");

        testMethodsAreSnakeCase.check(suite);
    }
}
