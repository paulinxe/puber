package com.puber.matching.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puber.matching.MatchingServiceApplication;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The smoke check underneath {@link ArchitectureRulesTest}.
 *
 * <p>Analyzer support for a new JDK routinely lags by months, and every structural rule in this
 * service is built on ArchUnit's ability to parse this project's bytecode. If ArchUnit silently
 * imported nothing, every rule in {@code ArchitectureRulesTest} would pass while asserting nothing
 * at all -- a green suite proving the opposite of what it claims. This test fails loudly in that
 * case instead.
 */
class ArchUnitReadsJava25ClassFilesTest {

    /** Java 25 compiles to class-file major version 69. */
    private static final int JAVA_25_CLASS_FILE_MAJOR_VERSION = 69;

    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    @Test
    @DisplayName("this service really is compiled to Java 25 bytecode")
    void compiles_to_java25_bytecode() throws IOException {
        try (InputStream classFile =
                getClass()
                        .getResourceAsStream(
                                "/com/puber/matching/MatchingServiceApplication.class")) {
            assertNotNull(
                    classFile, "MatchingServiceApplication.class is not on the test classpath");
            DataInputStream in = new DataInputStream(classFile);
            assertEquals(CLASS_FILE_MAGIC, in.readInt(), "not a class file");
            in.readUnsignedShort(); // minor version
            assertEquals(
                    JAVA_25_CLASS_FILE_MAJOR_VERSION,
                    in.readUnsignedShort(),
                    "the Java toolchain is not 25 -- the rest of this check would prove nothing");
        }
    }

    @Test
    @DisplayName("ArchUnit parses that bytecode rather than importing an empty set")
    void arch_unit_imports_java25_classes() {
        JavaClasses imported = new ClassFileImporter().importPackages("com.puber.matching");

        assertFalse(
                imported.isEmpty(),
                "ArchUnit imported no classes -- every structural rule would be vacuous");

        JavaClass application = imported.get(MatchingServiceApplication.class);
        assertTrue(
                application.getMethods().stream()
                        .anyMatch(method -> method.getName().equals("main")),
                "ArchUnit found the class but not its members -- the class file was not fully parsed");
    }
}
