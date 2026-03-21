package ai.jacc.durableThreads.e2e;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: multiple freeze/restore cycles in the SAME JVM process.
 *
 * <p>Validates that a restored thread can call {@code Durable.freeze()} again,
 * and the main thread can restore it multiple times in a loop — all within
 * a single JVM process.</p>
 */
@Tag("e2e")
class MultiCycleFreezeRestoreIT {

    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        classpath = ChildJvm.buildClasspath();
    }

    @Test
    void multipleFreezeRestoreCyclesInSameJvm() throws Exception {
        Path snapshotFile = Files.createTempFile("multi-cycle-snapshot-", ".dat");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.MultiCycleFreezeRestoreProgram",
                    classpath, port,
                    new String[]{snapshotFile.toString()}, 120);

            // 1. No exceptions on stderr
            String filteredStderr = filterStderr(result.stderr());
            assertEquals("", filteredStderr,
                    "No exceptions should be printed to stderr. Full stderr:\n"
                    + result.stderr());

            // 2. Clean exit
            assertEquals(0, result.exitCode(),
                    "Child JVM should exit with code 0. Stdout:\n" + result.stdout()
                    + "\nStderr:\n" + result.stderr());

            // 3. No restore failures
            assertFalse(result.stdout().contains("RESTORE_FAILED"),
                    "Restore should not fail. Stdout:\n" + result.stdout()
                    + "\nStderr:\n" + result.stderr());

            // 4. All iterations complete
            assertTrue(result.stdout().contains("ALL_COMPLETE"),
                    "All cycles should complete. Stdout:\n" + result.stdout());

            // 5. Verify the output sequence: i=1..20 with freeze/restore cycles
            List<String> userLines = extractUserOutput(result.stdout());

            // Build expected output: i=1..5, freeze, restore_cycle=1, resumed, i=6..10, freeze, etc.
            // Every multiple of 5 triggers a freeze, including i=20.
            List<String> expected = new ArrayList<String>();
            int cycle = 0;
            for (int i = 1; i <= 20; i++) {
                expected.add("i=" + i);
                if (i % 5 == 0) {
                    expected.add("About to freeze!");
                    cycle++;
                    expected.add("RESTORE_CYCLE=" + cycle);
                    expected.add("Resumed!");
                }
            }
            expected.add("Done!");

            assertEquals(expected, userLines,
                    "Output should match expected multi-cycle freeze/restore sequence.\n"
                    + "Expected:\n" + String.join("\n", expected)
                    + "\n\nGot:\n" + String.join("\n", userLines));

        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    private static String filterStderr(String stderr) {
        return Arrays.stream(stderr.split("\n"))
                .filter(line -> !line.startsWith("Listening for transport dt_socket"))
                .filter(line -> !line.startsWith("Picked up"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.joining("\n"));
    }

    private static List<String> extractUserOutput(String stdout) {
        return Arrays.stream(stdout.split("\n"))
                .filter(line -> !line.equals("ALL_COMPLETE"))
                .filter(line -> !line.startsWith("RESTORE_COUNT="))
                .filter(line -> !line.startsWith("RESTORE_FAILED"))
                .filter(line -> !line.startsWith("Listening for transport dt_socket"))
                .filter(line -> !line.startsWith("Picked up"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.toList());
    }
}
