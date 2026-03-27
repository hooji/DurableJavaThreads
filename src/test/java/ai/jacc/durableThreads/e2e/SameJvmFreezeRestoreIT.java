package ai.jacc.durableThreads.e2e;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: freeze and restore in the SAME JVM process.
 *
 * <p>Validates that when a thread is frozen and then restored in the same
 * JVM, the restore reuses the existing JDI connection (rather than opening
 * a new one) and the restored thread runs to completion.</p>
 *
 * <p>Requires: {@code mvn package -DskipTests} to build the agent jar first.</p>
 */
@Tag("e2e")
class SameJvmFreezeRestoreIT {

    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        classpath = ChildJvm.buildClasspath();
    }

    @Test
    void freezeAndRestoreInSameJvm() throws Exception {
        Path snapshotFile = Files.createTempFile("same-jvm-snapshot-", ".dat");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.SameJvmFreezeRestoreProgram",
                    classpath, port,
                    new String[]{snapshotFile.toString()}, 60);

            // 1. No exceptions should appear on stderr (filter JDWP listening line)
            String filteredStderr = filterStderr(result.stderr());
            assertEquals("", filteredStderr,
                    "No exceptions should be printed to stderr. Full stderr:\n"
                    + result.stderr());

            // 2. Process should exit cleanly
            assertEquals(0, result.exitCode(),
                    "Child JVM should exit with code 0. Stdout:\n" + result.stdout()
                    + "\nStderr:\n" + result.stderr());

            // 3. Restore should complete (no RESTORE_FAILED)
            assertFalse(result.stdout().contains("RESTORE_FAILED"),
                    "Restore should not fail. Stdout:\n" + result.stdout()
                    + "\nStderr:\n" + result.stderr());

            assertTrue(result.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + result.stdout());

            // 4. Output should match expected sequence exactly
            List<String> userLines = extractUserOutput(result.stdout());
            List<String> expected = Arrays.asList(
                    "i=2", "i=3", "i=4", "i=5",
                    "About to freeze!",
                    "Thread frozen...",
                    "Resumed!",
                    "i=6", "i=7", "i=8", "i=9", "i=10", "i=11", "i=12",
                    "Done!");
            assertEquals(expected, userLines,
                    "Output should match expected freeze/restore sequence. Got:\n"
                    + result.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    /**
     * Filter stderr to remove expected JDWP/agent lines, leaving only real errors.
     */
    private static String filterStderr(String stderr) {
        return Arrays.stream(stderr.split("\n"))
                .filter(line -> !line.startsWith("Listening for transport dt_socket"))
                .filter(line -> !line.startsWith("Ignoring cmd"))
                .filter(line -> !line.startsWith("Picked up"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Extract user-visible output lines, excluding infrastructure markers.
     */
    private static List<String> extractUserOutput(String stdout) {
        return Arrays.stream(stdout.split("\n"))
                .filter(line -> !line.equals("RESTORE_COMPLETE"))
                .filter(line -> !line.startsWith("RESTORE_FAILED"))
                .filter(line -> !line.startsWith("Listening for transport dt_socket"))
                .filter(line -> !line.startsWith("Ignoring cmd"))
                .filter(line -> !line.startsWith("Picked up"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.toList());
    }
}
