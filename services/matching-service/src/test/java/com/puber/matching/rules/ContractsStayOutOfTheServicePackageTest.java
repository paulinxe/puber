package com.puber.matching.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Every generated contract class lands outside {@code com.puber.matching}. */
class ContractsStayOutOfTheServicePackageTest {

    /** The copy `make contract-config` writes, which is the one codegen actually reads. */
    private static final Path CONTRACTS = Path.of("build/contracts/proto");

    private static final String PROTO = ".proto";

    private static final String REQUIRED_PREFIX = "com.puber.contracts.";

    private static final Pattern JAVA_PACKAGE =
            Pattern.compile("^\\s*option\\s+java_package\\s*=\\s*\"([^\"]*)\"\\s*;");

    @Test
    @DisplayName("AC11a: every contract declares a java_package under com.puber.contracts")
    void every_contract_generates_outside_the_service_package() throws IOException {
        List<Path> contracts = contractFiles();

        // A scan that reads nothing passes forever, and this one runs from a build directory that
        // a bare `./gradlew test` can leave empty.
        assertFalse(
                contracts.isEmpty(),
                () ->
                        "no .proto found under "
                                + CONTRACTS.toAbsolutePath()
                                + " -- run `make build`, which copies contracts/proto into place;"
                                + " this test proves nothing against an empty directory");

        for (Path contract : contracts) {
            assertTrue(
                    declaredJavaPackage(contract).startsWith(REQUIRED_PREFIX),
                    () ->
                            contract
                                    + " declares java_package "
                                    + declaredJavaPackageOrNothing(contract)
                                    + ", which puts its generated classes inside"
                                    + " ArchitectureRulesTest's com.puber.matching scan and back"
                                    + " inside modelDependsOnNothingFrameworkFlavoured's"
                                    + " com.puber.. allowance. Fix the .proto, not the rule.");
        }
    }

    private static List<Path> contractFiles() throws IOException {
        if (!Files.isDirectory(CONTRACTS)) {
            return List.of();
        }
        try (Stream<Path> tree = Files.walk(CONTRACTS)) {
            return tree.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(PROTO))
                    .toList();
        }
    }

    private static String declaredJavaPackage(Path contract) {
        String declared = declaredJavaPackageOrNothing(contract);
        assertFalse(
                declared.isEmpty(),
                () ->
                        contract
                                + " declares no java_package, so protoc derives one from the proto"
                                + " package and the guarantee this test exists for is unstated");
        return declared;
    }

    private static String declaredJavaPackageOrNothing(Path contract) {
        try {
            for (String line : Files.readAllLines(contract)) {
                Matcher declaration = JAVA_PACKAGE.matcher(line);
                if (declaration.find()) {
                    return declaration.group(1);
                }
            }
            return "";
        } catch (IOException unreadable) {
            throw new IllegalStateException("could not read " + contract, unreadable);
        }
    }
}
