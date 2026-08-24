package com.puber.matching.rules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Test naming, enforced rather than reviewed.
 *
 * <p>A separate class from {@link ArchitectureRulesTest} because that one is declared {@code
 * DoNotIncludeTests}: a rule about test methods placed there would scan no test code and pass
 * forever. The import options are mutually exclusive, so the split is forced by the mechanics, not
 * a preference.
 *
 * <p><strong>This class covers {@code src/test/java} only.</strong> The integration suite compiles
 * to a different output directory, which is neither on this run's classpath nor matched by {@code
 * OnlyIncludeTests}, so a camelCase {@code @Test} over there passed until {@code
 * TestNamingRulesIntegrationTest} was added to cover it. The two rule bodies are copies; change
 * both.
 */
@AnalyzeClasses(
        packages = "com.puber.matching",
        importOptions = ImportOption.OnlyIncludeTests.class)
class TestNamingRulesTest {

    /**
     * AGENTS.md: test methods are snake_case. Expressed as "no uppercase letter" rather than as a
     * snake_case pattern, because the failure message then names the offending method instead of
     * asserting a charset nobody reads.
     *
     * <p>Only {@code @Test} methods: helpers and lifecycle methods stay camelCase on purpose, so
     * the casing tells you which methods are test cases.
     */
    @ArchTest
    static final ArchRule testMethodsAreSnakeCase =
            noMethods()
                    .that()
                    .areAnnotatedWith(Test.class)
                    .should()
                    .haveNameMatching(".*[A-Z].*")
                    .because(
                            "AGENTS.md: test method names are snake_case -- prices_a_five_km_trip"
                                    + " reads, pricesAFiveKmTrip does not");
}
