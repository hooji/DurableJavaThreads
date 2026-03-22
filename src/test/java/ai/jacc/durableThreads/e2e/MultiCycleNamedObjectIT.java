package ai.jacc.durableThreads.e2e;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: multi-cycle freeze/restore with named objects verifies that
 * field mutations are not duplicated during the skip pass.
 */
@Tag("e2e")
class MultiCycleNamedObjectIT {

    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        classpath = ChildJvm.buildClasspath();
    }

    @Test
    void fieldMutationsNotDuplicatedDuringSkipPass() throws Exception {
        Path snapshotFile = Files.createTempFile("multi-cycle-named-", ".dat");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.MultiCycleNamedObjectProgram",
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

            // 3. nFreezes == nResumptions (field mutations not duplicated)
            assertTrue(result.stdout().contains("COUNTS_MATCH"),
                    "nFreezes and nResumptions should be equal. "
                    + "If COUNTS_MISMATCH, field mutations were duplicated "
                    + "during the skip pass. Stdout:\n" + result.stdout());

        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    private static String filterStderr(String stderr) {
        return Arrays.stream(stderr.split("\n"))
                .filter(line -> !line.startsWith("Listening for transport dt_socket"))
                .filter(line -> !line.startsWith("Picked up"))
                .filter(line -> !line.startsWith("[DurableThreads] NOTE:"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.joining("\n"));
    }
}
