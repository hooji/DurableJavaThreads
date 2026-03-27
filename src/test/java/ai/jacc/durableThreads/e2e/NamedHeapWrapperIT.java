package ai.jacc.durableThreads.e2e;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: named heap objects via a wrapper class.
 *
 * <p>Exercises the pattern where freeze() is called from a method on the
 * named object, but the bottom frame is a wrapper class (Worker) with a
 * final field pointing to the named object.</p>
 */
@Tag("e2e")
class NamedHeapWrapperIT {

    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        classpath = ChildJvm.buildClasspath();
    }

    @Test
    void wrapperFinalFieldResolvesToReplacementOnRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("named-heap-wrapper-", ".dat");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.NamedHeapWrapperProgram",
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
                    "Before freeze should show original values. Stdout:\n"
                    + result.stdout());

            // 5. After restore: replacement object's values (NOT null/0!)
            assertTrue(result.stdout().contains("AFTER label=replaced counter=99"),
                    "After restore should show replacement values, not null/0. "
                    + "If this fails, the wrapper's final field did not correctly "
                    + "resolve to the replacement object. Stdout:\n"
                    + result.stdout());

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
