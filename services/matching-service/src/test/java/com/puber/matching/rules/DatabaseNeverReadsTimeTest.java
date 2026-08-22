package com.puber.matching.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No SQL anywhere in production sources asks the database what time it is.
 *
 * <p>A text scan, because {@code timeIsReadOnlyThroughTheClock} reads bytecode and cannot see a
 * {@code .sql} file or a query held in a string.
 *
 * <p>{@code default now()} on a column counts, and is the worst case: no test can advance a clock
 * inside the database. See project-context.md, "Never let the database tell the time".
 */
class DatabaseNeverReadsTimeTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

    private static final String JAVA = ".java";
    private static final String SQL = ".sql";

    private static final Set<Path> EXEMPT =
            Set.of(Path.of("com/puber/matching/shared/strategy/SystemClock.java"));

    /**
     * Every way of asking Postgres what time it is.
     *
     * <p>{@code \b} means "the name must start here", so {@code wallClockNow()} is not a match --
     * its "now" is stuck to the end of "Clock". Without that, our own interface would fail the
     * build.
     *
     * <p>Some entries end in {@code (} and some do not, because not all of these are function
     * calls: {@code default 'now'::timestamptz} reads the clock with no brackets at all. {@code
     * localtime} needs its own entry because {@code \blocaltime\b} does not match the longer {@code
     * localtimestamp}.
     */
    private static final List<Pattern> DATABASE_TIME_READS =
            List.of(
                    compile("\\bnow\\s*\\("),
                    compile("\\bcurrent_timestamp\\b"),
                    compile("\\bcurrent_date\\b"),
                    compile("\\bcurrent_time\\b"),
                    compile("\\blocaltimestamp\\b"),
                    compile("\\blocaltime\\b"),
                    compile("\\bclock_timestamp\\s*\\("),
                    compile("\\btransaction_timestamp\\s*\\("),
                    compile("\\bstatement_timestamp\\s*\\("),
                    compile("\\btimeofday\\s*\\("),
                    compile("'\\s*(now|today|yesterday|tomorrow|allballs)\\s*'"));

    @Test
    @DisplayName("AC1: no migration asks the database for the time")
    void noMigrationAsksTheDatabaseForTheTime() throws IOException {
        ScanResult migrations = scan(MAIN_RESOURCES, SQL);

        // A check on the test, not on the code: a wrong root scans nothing, finds nothing, and
        // stays green forever.
        assertTrue(
                migrations.filesScanned() > 0,
                () ->
                        "scanned no "
                                + SQL
                                + " file at all under "
                                + MAIN_RESOURCES.toAbsolutePath()
                                + " -- this scan proves nothing until it reads the migrations");
        assertTrue(migrations.violations().isEmpty(), migrations::report);
    }

    @Test
    @DisplayName("AC1: no Java file carries SQL that asks the database for the time")
    void noJavaFileCarriesSqlThatAsksForTheTime() throws IOException {
        ScanResult sources = scan(MAIN_JAVA, JAVA);

        assertTrue(
                sources.filesScanned() > 0,
                () ->
                        "scanned no "
                                + JAVA
                                + " file at all under "
                                + MAIN_JAVA.toAbsolutePath()
                                + " -- this scan proves nothing until it reads the sources");
        assertTrue(sources.violations().isEmpty(), sources::report);
    }

    @Test
    @DisplayName("AC1: the scan reports the file, the line and the pattern of a planted violation")
    void reportsWhereTheViolationIs(@TempDir Path plantedTree) throws IOException {
        Path migration = plantedTree.resolve("V9__gives_a_column_a_default.sql");
        Files.write(
                migration,
                List.of(
                        "-- A comment that says nothing a scanner should care about.",
                        "alter table fare_rules",
                        "    add column created_at timestamptz not null default NOW();"));

        ScanResult planted = scan(plantedTree, SQL);

        assertEquals(1, planted.filesScanned(), "the scan did not read the planted file");
        assertEquals(
                1,
                planted.violations().size(),
                () -> "expected exactly one violation, got: " + planted.report());
        Violation violation = planted.violations().get(0);
        assertEquals(migration, violation.file(), "the wrong file was blamed");
        assertEquals(3, violation.line(), "the wrong line was blamed");
        assertTrue(
                violation.pattern().contains("now"),
                () -> "the wrong pattern was blamed: " + violation.pattern());
    }

    @Test
    @DisplayName("AC1: the clock reads with no brackets are caught too")
    void catchesTheClockReadsThatCarryNoBracket(@TempDir Path plantedTree) throws IOException {
        // Four real Postgres clock reads, none containing `now(`.
        List<String> everyLineIsAViolation =
                List.of(
                        "alter table t add column a timestamptz not null default 'now'::timestamptz;",
                        "alter table t add column b time not null default localtime;",
                        "alter table t add column c text not null default timeofday();",
                        "alter table t add column d date not null default 'today'::date;");
        Path migration = plantedTree.resolve("V9__reads_the_clock_without_brackets.sql");
        Files.write(migration, everyLineIsAViolation);

        ScanResult planted = scan(plantedTree, SQL);

        assertEquals(1, planted.filesScanned(), "the scan did not read the planted file");
        for (int lineNumber = 1; lineNumber <= everyLineIsAViolation.size(); lineNumber++) {
            int line = lineNumber;
            assertTrue(
                    planted.violations().stream().anyMatch(violation -> violation.line() == line),
                    () ->
                            "line "
                                    + line
                                    + " asks the database for the time and was not reported: "
                                    + everyLineIsAViolation.get(line - 1));
        }
    }

    @Test
    @DisplayName("AC1: the scan does not fire on our own method name or on ordinary prose")
    void doesNotFireOnWhatOnlyLooksLikeAClockRead(@TempDir Path plantedTree) throws IOException {
        Path source = plantedTree.resolve("NotAViolation.java");
        Files.write(
                source,
                List.of(
                        "// The fare is quoted from the rules in force at the time of the request.",
                        "Instant recordedAt = clock.wallClockNow();",
                        "Deadline offer = clock.deadlineIn(OFFER_TIMEOUT);"));

        ScanResult scanned = scan(plantedTree, JAVA);

        assertEquals(1, scanned.filesScanned(), "the scan did not read the planted file");
        assertTrue(
                scanned.violations().isEmpty(),
                () -> "a false positive fails the build on correct code: " + scanned.report());
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private static ScanResult scan(Path root, String extension) throws IOException {
        if (!Files.isDirectory(root)) {
            return new ScanResult(0, List.of());
        }
        List<Violation> violations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> tree = Files.walk(root)) {
            files =
                    tree.filter(Files::isRegularFile)
                            .filter(file -> file.getFileName().toString().endsWith(extension))
                            .filter(file -> !EXEMPT.contains(root.relativize(file)))
                            .sorted()
                            .toList();
        }
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
                String line = lines.get(lineNumber - 1);
                for (Pattern function : DATABASE_TIME_READS) {
                    if (function.matcher(line).find()) {
                        violations.add(new Violation(file, lineNumber, function.pattern()));
                    }
                }
            }
        }
        return new ScanResult(files.size(), violations);
    }

    private record Violation(Path file, int line, String pattern) {

        private String describe() {
            return file + ":" + line + " matches " + pattern;
        }
    }

    private record ScanResult(int filesScanned, List<Violation> violations) {

        /**
         * Names the first violation, so the message says where to look.
         *
         * <p>Only ever read as a failure message, which JUnit asks for after the assertion has
         * already failed -- so there is always at least one violation by then.
         */
        private String report() {
            String first = violations.get(0).describe();
            return violations.size() == 1
                    ? "SQL reads the time: " + first
                    : "SQL reads the time: "
                            + first
                            + " (and "
                            + (violations.size() - 1)
                            + " more)";
        }
    }
}
