package ai.jacc.durableThreads.e2e;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: freeze/restore when two for-loops reuse the same loop variable
 * name and slot. Regression test for the deduplication bug where
 * emitLocalVariables() dropped the second loop's scope entry, making 'i'
 * invisible to JDI at the freeze point.
 */
@Tag("e2e")
class DuplicateLoopVarIT {

    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        classpath = ChildJvm.buildClasspath();
    }

    @Test
    void freezeInSecondLoopWithDuplicateVarName() throws Exception {
        Path snapshotFile = Files.createTempFile("duploop-snapshot-", ".dat");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.DuplicateLoopVarFreezeProgram",
                    classpath, port,
                    new String[]{snapshotFile.toString()}, 60);

            // 1. No exceptions on stderr
            String filteredStderr = Arrays.stream(result.stderr().split("\n"))
                    .filter(line -> !line.startsWith("Listening for transport dt_socket"))
                    .filter(line -> !line.startsWith("Picked up"))
                    .filter(line -> !line.trim().isEmpty())
                    .collect(Collectors.joining("\n"));
            assertEquals("", filteredStderr,
                    "No exceptions should be printed to stderr. Full stderr:\n"
                    + result.stderr());

            // 2. Clean exit
            assertEquals(0, result.exitCode(),
                    "Child JVM should exit with code 0. Stdout:\n" + result.stdout()
                    + "\nStderr:\n" + result.stderr());

            // 3. Restore succeeded
            assertFalse(result.stdout().contains("RESTORE_FAILED"),
                    "Restore should not fail. Stdout:\n" + result.stdout()
                    + "\nStderr:\n" + result.stderr());
            assertTrue(result.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + result.stdout());

            // 4. The loop variable 'i' was correctly restored in the second loop
            assertTrue(result.stdout().contains("Resumed! i=2"),
                    "After restore, i should be 2 (the freeze point). Stdout:\n"
                    + result.stdout());

            // 5. Second loop completed with correct result
            assertTrue(result.stdout().contains("SECOND_LOOP_RESULT=20"),
                    "Second loop result should be 20 (sum of first loop 10 + 0+1+2+3+4). Stdout:\n"
                    + result.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }
}
