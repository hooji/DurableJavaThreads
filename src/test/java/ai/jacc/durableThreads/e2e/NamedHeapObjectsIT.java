package ai.jacc.durableThreads.e2e;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: named heap objects — auto-naming "this" during freeze and
 * substituting a replacement object during restore.
 */
@Tag("e2e")
class NamedHeapObjectsIT {

    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        classpath = ChildJvm.buildClasspath();
    }

    @Test
    void autoNamedThisIsReplacedOnRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("named-heap-snapshot-", ".dat");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.NamedHeapObjectsProgram",
                    classpath, port,
                    new String[]{snapshotFile.toString()}, 60);

            // 1. No unexpected exceptions on stderr
            String filteredStderr = filterStderr(result.stderr());
            assertEquals("", filteredStderr,
                    "No exceptions on stderr. Full stderr:\n" + result.stderr());

            // 2. Clean exit
            assertEquals(0, result.exitCode(),
                    "Should exit cleanly. Stdout:\n" + result.stdout()
                    + "\nStderr:\n" + result.stderr());

            // 3. Restore succeeded
            assertTrue(result.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + result.stdout());

            // 4. Before freeze: original object's values
            assertTrue(result.stdout().contains("BEFORE label=original counter=42"),
                    "Before freeze should show original values. Stdout:\n" + result.stdout());

            // 5. After restore: replacement object's values
            assertTrue(result.stdout().contains("AFTER label=replaced counter=99"),
                    "After restore should show replacement values. Stdout:\n" + result.stdout());

        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    private static String filterStderr(String stderr) {
        return Arrays.stream(stderr.split("\n"))
                .filter(line -> !line.startsWith("Listening for transport dt_socket"))
                .filter(line -> !line.startsWith("Ignoring cmd"))
                .filter(line -> !line.startsWith("Picked up"))
                .filter(line -> !line.startsWith("[DurableThreads] NOTE:"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.joining("\n"));
    }
}
