package ai.jacc.durableThreads.e2e;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests that spawn child JVM processes with the
 * durable-threads agent and JDWP enabled.
 *
 * <p>These tests exercise the REAL freeze/restore path through JDI.
 * Each test launches one or more child JVMs and validates the results
 * via stdout output.</p>
 *
 * <p>Requires: {@code mvn package -DskipTests} to build the agent jar first.</p>
 */
@Tag("e2e")
class EndToEndFreezeRestoreIT {

    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        classpath = ChildJvm.buildClasspath();
    }

    /**
     * Assert that a restore completed successfully — the restored thread
     * didn't throw and RESTORE_COMPLETE was printed.
     */
    private static void assertRestoreSucceeded(ChildJvm.Result restoreResult) {
        assertFalse(restoreResult.stdout().contains("RESTORE_FAILED"),
                "Restored thread threw an exception. Stderr:\n" + restoreResult.stderr());
        assertTrue(restoreResult.stdout().contains("RESTORE_COMPLETE"),
                "Restore should complete. Stdout:\n" + restoreResult.stdout());
    }

    /**
     * Extract the "user output" lines from restore stdout — lines printed by
     * the restored thread, excluding the RestoreProgram infrastructure lines
     * (SNAPSHOT_LOADED, FRAME_COUNT, RESTORE_COMPLETE).
     */
    private static java.util.List<String> extractUserOutput(String stdout) {
        return java.util.Arrays.stream(stdout.split("\n"))
                .filter(line -> !line.startsWith("SNAPSHOT_LOADED="))
                .filter(line -> !line.startsWith("FRAME_COUNT="))
                .filter(line -> !line.equals("RESTORE_COMPLETE"))
                .filter(line -> !line.startsWith("Listening for transport dt_socket"))
                .filter(line -> !line.startsWith("Ignoring cmd"))
                .filter(line -> !line.trim().isEmpty())
                .collect(java.util.stream.Collectors.toList());
    }

    // ===================================================================
    // Basic tests
    // ===================================================================

    @Test
    @DisplayName("JDWP port auto-discovery: freeze/restore with no explicit address")
    void autoDiscoveryFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-autodiscovery-", ".bin");
        try {
            // Step 1: Freeze with auto-assigned JDWP port (jdwpPort = -1)
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.FreezeProgram",
                    classpath, -1,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== AUTO-DISCOVERY FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== AUTO-DISCOVERY FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Freeze with auto-discovered port should complete. Stdout:\n" + freezeResult.stdout()
                            + "\nStderr:\n" + freezeResult.stderr());
            assertTrue(Files.size(snapshotFile) > 100, "Snapshot file should have content");

            // Step 2: Restore in a NEW JVM with auto-assigned JDWP port
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, -1,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== AUTO-DISCOVERY RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== AUTO-DISCOVERY RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertRestoreSucceeded(restoreResult);
            assertTrue(restoreResult.stdout().contains("AFTER_FREEZE=42"),
                    "Restored thread should output counter=42. Stdout:\n" + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("Agent loads successfully in child JVM")
    void agentLoads() throws Exception {
        ChildJvm.Result result = ChildJvm.run(
                "ai.jacc.durableThreads.e2e.AgentLoadProgram",
                classpath, 0, new String[0], 30);

        assertEquals(0, result.exitCode(),
                "Child JVM should exit cleanly. Stderr:\n" + result.stderr());
        assertTrue(result.stdout().contains("AGENT_LOADED=true"),
                "Agent should be loaded. Stdout:\n" + result.stdout());
    }

    @Test
    @DisplayName("Freeze captures snapshot to file")
    void freezeWritesSnapshot() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-snapshot-", ".bin");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.FreezeProgram",
                    classpath, port,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== FREEZE STDOUT ===\n" + result.stdout());
            System.out.println("=== FREEZE STDERR ===\n" + result.stderr());

            // The freeze process should output BEFORE_FREEZE and FREEZE_COMPLETE
            assertTrue(result.stdout().contains("BEFORE_FREEZE=42"),
                    "Should print state before freeze. Stdout:\n" + result.stdout());
            assertTrue(result.stdout().contains("FREEZE_COMPLETE"),
                    "Should complete the freeze. Stdout:\n" + result.stdout());

            // The original thread should NOT print AFTER_FREEZE (it's terminated)
            assertFalse(result.stdout().contains("AFTER_FREEZE"),
                    "Original thread should not continue past freeze. Stdout:\n" + result.stdout());

            // Snapshot file should exist and have content
            assertTrue(Files.exists(snapshotFile), "Snapshot file should exist");
            assertTrue(Files.size(snapshotFile) > 100,
                    "Snapshot file should have content (size=" + Files.size(snapshotFile) + ")");
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("Freeze then restore: snapshot loads in second JVM")
    void freezeAndRestoreLoadsSnapshot() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-snapshot-", ".bin");
        try {
            // Step 1: Freeze
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.FreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Freeze should complete. Stderr:\n" + freezeResult.stderr());
            assertFalse(freezeResult.stdout().contains("AFTER_FREEZE"),
                    "Original thread should not continue past freeze");
            assertTrue(Files.size(snapshotFile) > 100, "Snapshot file should have content");

            // Step 2: Restore in a NEW JVM process — verify snapshot loads
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load snapshot in second JVM. Stdout:\n" + restoreResult.stdout());
            assertRestoreSucceeded(restoreResult);

            // Restored thread should produce post-freeze output with correct values
            assertTrue(restoreResult.stdout().contains("AFTER_FREEZE=42"),
                    "Restored thread should output counter=42. Stdout:\n" + restoreResult.stdout());
            assertTrue(restoreResult.stdout().contains("MESSAGE=hello-from-freeze"),
                    "Restored thread should output message. Stdout:\n" + restoreResult.stdout());

            // Restored thread must NOT replay pre-freeze output
            java.util.List<String> userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.Arrays.asList("AFTER_FREEZE=42", "MESSAGE=hello-from-freeze"),
                    userLines,
                    "Restore output should be exactly the post-freeze lines, no replay. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("Sequential freeze captures multiple snapshots to disk")
    void sequentialFreezeCapturesSnapshots() throws Exception {
        // This test runs a loop that freezes periodically.
        // Since the original thread is terminated after each freeze(),
        // in a single-process test we expect freeze to succeed for the
        // FIRST iteration (i=0), then the thread dies.
        // Full sequential freeze/restore across iterations requires the
        // complete restore path.
        Path snapshotDir = Files.createTempDirectory("durable-seq-");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.SequentialFreezeProgram",
                    classpath, port,
                    new String[]{snapshotDir.toString()}, 120);

            System.out.println("=== SEQUENTIAL STDOUT ===\n" + result.stdout());
            if (!result.stderr().trim().isEmpty()) {
                System.out.println("=== SEQUENTIAL STDERR ===\n" + result.stderr());
            }

            // At minimum, the first freeze (i=0) should succeed
            assertTrue(result.stdout().contains("FREEZE i=0"),
                    "First freeze should succeed. Stdout:\n" + result.stdout());

            // Verify snapshot file was written
            long snapshotCount;
            try (java.util.stream.Stream<Path> files = Files.list(snapshotDir)) {
                snapshotCount = files.filter(p -> p.toString().endsWith(".bin")).count();
            }
            assertTrue(snapshotCount >= 1,
                    "At least one snapshot file should be written. Count: " + snapshotCount);
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(snapshotDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
    }

    // ===================================================================
    // E2E freeze/restore scenarios (duplicated from unit tests)
    // ===================================================================

    @Test
    @DisplayName("E2E: Deep call chain freeze and restore")
    void deepCallChainFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-deep-", ".bin");
        try {
            // Step 1: Freeze — 3 levels deep (outer → middle → inner)
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.DeepCallChainFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== DEEP CHAIN FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== DEEP CHAIN FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Deep chain freeze should complete. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("OUTER_BEFORE=15"),
                    "Should execute outer method. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("MIDDLE_BEFORE=30"),
                    "Should execute middle method. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("INNER_BEFORE=37"),
                    "Should execute inner method. Stdout:\n" + freezeResult.stdout());
            // Original thread should NOT reach post-freeze code
            assertFalse(freezeResult.stdout().contains("INNER_AFTER"),
                    "Original thread should not continue past freeze");
            assertTrue(Files.size(snapshotFile) > 100, "Snapshot file should have content");

            // Step 2: Restore in a NEW JVM
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== DEEP CHAIN RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== DEEP CHAIN RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load deep-chain snapshot. Stdout:\n" + restoreResult.stdout());
            assertRestoreSucceeded(restoreResult);

            // Verify the snapshot has multiple frames (at least 3: outer, middle, inner)
            assertTrue(restoreResult.stdout().contains("FRAME_COUNT="),
                    "Should report frame count. Stdout:\n" + restoreResult.stdout());

            // Restored thread should produce post-freeze output with correct values
            // innerMethod: computed(37) + 80 = 117; middleMethod: 117 + 100 = 217; outerMethod: 217 + 1000 = 1217
            java.util.List<String> userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.Arrays.asList(
                    "INNER_AFTER=117",
                    "MIDDLE_AFTER=117",
                    "OUTER_AFTER=217",
                    "DEEP_RESULT=1217"),
                    userLines,
                    "Restore should produce only post-freeze output with correct values. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Loop freeze captures correct iteration state")
    void loopFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-loop-", ".bin");
        try {
            // Step 1: Freeze inside a loop at iteration 4
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.LoopFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== LOOP FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== LOOP FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Loop freeze should complete. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("BEFORE_FREEZE sum=10"),
                    "Should capture sum=10 at i=4. Stdout:\n" + freezeResult.stdout());
            // Original thread should NOT print AFTER_FREEZE or FINAL_SUM
            assertFalse(freezeResult.stdout().contains("AFTER_FREEZE"),
                    "Original thread should not continue past freeze");
            assertFalse(freezeResult.stdout().contains("FINAL_SUM"),
                    "Original thread should not complete the loop");
            assertTrue(Files.size(snapshotFile) > 100, "Snapshot file should have content");

            // Step 2: Restore in a NEW JVM
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== LOOP RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== LOOP RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load loop snapshot. Stdout:\n" + restoreResult.stdout());
            assertRestoreSucceeded(restoreResult);

            // Restored thread should continue from i=4 (after freeze), then loop i=5..9
            // Expected: AFTER_FREEZE, ITERATION i=5..9, FINAL_SUM=45, LOOP_RESULT=45
            java.util.List<String> userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.Arrays.asList(
                    "AFTER_FREEZE i=4 sum=10",
                    "ITERATION i=5 sum=15",
                    "ITERATION i=6 sum=21",
                    "ITERATION i=7 sum=28",
                    "ITERATION i=8 sum=36",
                    "ITERATION i=9 sum=45",
                    "FINAL_SUM=45",
                    "LOOP_RESULT=45"),
                    userLines,
                    "Restore should continue loop from i=5, no replay of i=0..4. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Return values preserved across freeze/restore")
    void returnValueFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-retval-", ".bin");
        try {
            // Step 1: Freeze between chained method calls
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.ReturnValueFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== RETVAL FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== RETVAL FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Return-value freeze should complete. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("BEFORE_FREEZE v1=15 v2=45 v3=43"),
                    "Should capture pre-freeze locals. Stdout:\n" + freezeResult.stdout());
            // Original thread should not continue past freeze
            assertFalse(freezeResult.stdout().contains("AFTER_FREEZE"),
                    "Original thread should not continue past freeze");
            assertTrue(Files.size(snapshotFile) > 100, "Snapshot file should have content");

            // Step 2: Restore in a NEW JVM
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== RETVAL RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== RETVAL RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load return-value snapshot. Stdout:\n" + restoreResult.stdout());
            assertRestoreSucceeded(restoreResult);

            // Restored thread should have preserved v1=15, v2=45, v3=43,
            // then compute v4=21, v5=121, CHAIN_RESULT=245
            java.util.List<String> userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.Arrays.asList(
                    "AFTER_FREEZE v1=15 v2=45 v3=43",
                    "AFTER_COMPUTE v4=21 v5=121",
                    "CHAIN_RESULT=245"),
                    userLines,
                    "Restore should produce only post-freeze output with correct values. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Heap objects (POJO, array, list) captured in freeze snapshot")
    void heapObjectFreezeCapture() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-heap-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.HeapObjectFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== HEAP FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== HEAP FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Heap object freeze should complete. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("BEFORE_FREEZE alice=Alice(30)"),
                    "Should capture alice before freeze. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("BEFORE_FREEZE scores.length=5"),
                    "Should capture scores before freeze. Stdout:\n" + freezeResult.stdout());

            // Heap should contain objects (Person, int[], ArrayList, Strings, etc.)
            assertTrue(freezeResult.stdout().contains("HEAP_SIZE="),
                    "Should report heap size. Stdout:\n" + freezeResult.stdout());

            // Original thread should NOT reach post-freeze code
            assertFalse(freezeResult.stdout().contains("AFTER_FREEZE"),
                    "Original thread should not continue past freeze");

            assertTrue(Files.size(snapshotFile) > 100, "Snapshot file should have content");

            // Step 2: Restore in a NEW JVM and verify object state
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== HEAP RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== HEAP RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load heap snapshot. Stdout:\n" + restoreResult.stdout());
            assertRestoreSucceeded(restoreResult);

            // Verify primitive local survived
            assertTrue(restoreResult.stdout().contains("AFTER_FREEZE primitiveLocal=99"),
                    "Primitive local should survive. Stdout:\n" + restoreResult.stdout());

            // Restored thread must NOT replay pre-freeze output
            assertFalse(restoreResult.stdout().contains("BEFORE_FREEZE"),
                    "Restore must not replay pre-freeze output. Stdout:\n" + restoreResult.stdout());

            // Verify object locals — report what we got for debugging
            String stdout = restoreResult.stdout();
            System.out.println("=== HEAP RESTORE OBJECT STATE ===");
            for (String line : stdout.split("\n")) {
                if (line.startsWith("AFTER_FREEZE")) {
                    System.out.println("  " + line);
                }
            }
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    // ===================================================================
    // E2E scenario tests: edge cases and stress scenarios
    // ===================================================================

    @Test
    @DisplayName("E2E: Recursive fibonacci freeze and restore")
    void fibonacciFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-fib-", ".bin");
        try {
            // Step 1: Freeze during fib(10) at n=5
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.FibonacciFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString(), "5"}, 60);

            System.out.println("=== FIB FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== FIB FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Fibonacci freeze should complete. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("FREEZING at n=5"),
                    "Should freeze at n=5. Stdout:\n" + freezeResult.stdout());
            assertFalse(freezeResult.stdout().contains("FIB_RESULT"),
                    "Original thread should not produce result");
            assertTrue(Files.size(snapshotFile) > 100, "Snapshot file should have content");

            // Snapshot should have multiple recursive frames
            assertTrue(freezeResult.stdout().contains("FRAME_COUNT="),
                    "Should report frame count. Stdout:\n" + freezeResult.stdout());

            // Step 2: Restore
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== FIB RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== FIB RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load fib snapshot. Stdout:\n" + restoreResult.stdout());
            assertRestoreSucceeded(restoreResult);

            // Restored thread must NOT replay the freeze-time output
            assertFalse(restoreResult.stdout().contains("FREEZING at n=5"),
                    "Restore must not replay pre-freeze output. Stdout:\n" + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Try-catch freeze preserves exception handling")
    void tryCatchFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-trycatch-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.TryCatchFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== TRYCATCH FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== TRYCATCH FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Try-catch freeze should complete. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("TRY_BEFORE"),
                    "Should execute code before freeze in try block");
            assertFalse(freezeResult.stdout().contains("TRY_AFTER"),
                    "Original thread should not continue past freeze");
            assertTrue(Files.size(snapshotFile) > 100);

            // Step 2: Restore — verify try/catch/finally semantics
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== TRYCATCH RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== TRYCATCH RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load try-catch snapshot");
            assertRestoreSucceeded(restoreResult);

            // Restored thread should continue in try block, execute finally
            java.util.List<String> userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.Arrays.asList(
                    "TRY_AFTER",
                    "FINALLY_EXECUTED",
                    "TRYCATCH_RESULT=before-after-end-finally"),
                    userLines,
                    "Restore should continue in try block without replaying TRY_BEFORE. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Nested loop freeze preserves both loop counters")
    void nestedLoopFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-nested-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.NestedLoopFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== NESTED FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== NESTED FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Nested loop freeze should complete. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("BEFORE_FREEZE i=2 j=3"),
                    "Should freeze at i=2, j=3. Stdout:\n" + freezeResult.stdout());
            assertFalse(freezeResult.stdout().contains("TOTAL_ITERATIONS"),
                    "Original thread should not complete");
            assertTrue(Files.size(snapshotFile) > 100);

            // Step 2: Restore
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== NESTED RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== NESTED RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load nested loop snapshot");
            assertRestoreSucceeded(restoreResult);

            // Restored thread should continue from (i=2, j=3), complete all 25 iterations
            java.util.List<String> userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.Arrays.asList(
                    "AFTER_FREEZE i=2 j=3 total=13",
                    "TOTAL_ITERATIONS=25",
                    "FROZE_AT_TOTAL=13"),
                    userLines,
                    "Restore should continue nested loop without replaying pre-freeze output. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Many typed local variables survive freeze/restore")
    void manyLocalsFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-locals-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.ManyLocalsFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== MANY LOCALS FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== MANY LOCALS FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Many locals freeze should complete. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("BEFORE_FREEZE a=10"),
                    "Should capture locals before freeze. Stdout:\n" + freezeResult.stdout());
            assertFalse(freezeResult.stdout().contains("AFTER_FREEZE"),
                    "Original thread should not continue past freeze");
            assertTrue(Files.size(snapshotFile) > 100);

            // Step 2: Restore — verify all typed locals survived
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== MANY LOCALS RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== MANY LOCALS RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load many-locals snapshot");
            assertRestoreSucceeded(restoreResult);

            // Restored thread should have all typed locals with correct values
            // j = d + (long)e = 30 + 15 = 45
            // result = 10 + 11 + 12 + 30 + 15 + 5 + 1 + 33 + 45 = 162
            java.util.List<String> userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.Arrays.asList(
                    "AFTER_FREEZE a=10 b=11 c=12 d=30 g=true h=33 j=45",
                    "MANY_LOCALS_RESULT=162"),
                    userLines,
                    "Restore should have correct typed locals, no replay. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Periodic freeze/restore (i%5==1) captures checkpoints")
    void periodicFreezeRestoreCapturesCheckpoints() throws Exception {
        Path snapshotDir = Files.createTempDirectory("durable-periodic-");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.PeriodicFreezeRestoreProgram",
                    classpath, port,
                    new String[]{snapshotDir.toString()}, 120);

            System.out.println("=== PERIODIC STDOUT ===\n" + result.stdout());
            if (!result.stderr().trim().isEmpty()) {
                System.out.println("=== PERIODIC STDERR ===\n" + result.stderr());
            }

            // First freeze at i=1 (1 % 5 == 1) should succeed
            assertTrue(result.stdout().contains("FREEZE i=1"),
                    "First periodic freeze should succeed. Stdout:\n" + result.stdout());

            // Verify at least one checkpoint file was written
            long checkpointCount;
            try (java.util.stream.Stream<Path> files = Files.list(snapshotDir)) {
                checkpointCount = files.filter(p -> p.toString().endsWith(".bin")).count();
            }
            assertTrue(checkpointCount >= 1,
                    "At least one checkpoint file should be written. Count: " + checkpointCount);
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(snapshotDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
    }

    // ===================================================================
    // Bytecode offset stress tests
    // ===================================================================

    @Test
    @DisplayName("E2E: Switch statement freeze (tableswitch/lookupswitch padding)")
    void switchFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-switch-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.SwitchFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== SWITCH FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== SWITCH FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Switch freeze should complete. Stdout:\n" + freezeResult.stdout());
            // mode=3 → computeStep(40)=80, transform(80)=160, lookupswitch default → computeStep(5)=10
            // acc=80+10=90, finalStep(90)=97
            assertTrue(freezeResult.stdout().contains("BEFORE_FREEZE acc=90 post=97"),
                    "Should capture correct pre-freeze state. Stdout:\n" + freezeResult.stdout());
            assertFalse(freezeResult.stdout().contains("AFTER_FREEZE"),
                    "Original thread should not continue past freeze");
            assertTrue(Files.size(snapshotFile) > 100);

            // Restore
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== SWITCH RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== SWITCH RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertRestoreSucceeded(restoreResult);

            // afterFreeze = 97 + 90 + 1000 = 1187
            java.util.List<String> userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.Arrays.asList(
                    "AFTER_FREEZE=1187",
                    "SWITCH_RESULT=1187"),
                    userLines,
                    "Restore should compute correct result after switch. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Mixed invoke types (virtual, static, interface, invokedynamic)")
    void mixedInvokesFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-mixed-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.MixedInvokesFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== MIXED FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== MIXED FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Mixed invokes freeze should complete. Stdout:\n" + freezeResult.stdout());
            // a=15, b=30, c=35, label="result-30-end" (length=14), d=13
            assertTrue(freezeResult.stdout().contains("BEFORE_FREEZE a=15 b=30 c=35 d=13"),
                    "Should capture correct pre-freeze state. Stdout:\n" + freezeResult.stdout());
            assertFalse(freezeResult.stdout().contains("AFTER_FREEZE"),
                    "Original thread should not continue past freeze");
            assertTrue(Files.size(snapshotFile) > 100);

            // Restore
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== MIXED RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== MIXED RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertRestoreSucceeded(restoreResult);

            // total = 15 + 30 + 35 + 14 = 94
            java.util.List<String> userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.Arrays.asList(
                    "AFTER_FREEZE a=15 b=30 c=35 d=13",
                    "LABEL=result-30-end",
                    "SB=val=15",
                    "MIXED_RESULT=93"),
                    userLines,
                    "Restore should preserve all typed locals and objects. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Complex control flow with many invokes")
    void complexControlFlowFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-complex-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.ComplexControlFlowFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== COMPLEX FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== COMPLEX FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Complex flow freeze should complete. Stdout:\n" + freezeResult.stdout());
            // value=84, a=168
            assertTrue(freezeResult.stdout().contains("BEFORE_FREEZE value=84 a=168"),
                    "Should capture correct pre-freeze state. Stdout:\n" + freezeResult.stdout());
            assertFalse(freezeResult.stdout().contains("AFTER_FREEZE"),
                    "Original thread should not continue past freeze");
            assertTrue(Files.size(snapshotFile) > 100);

            // Restore
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== COMPLEX RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== COMPLEX RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertRestoreSucceeded(restoreResult);

            // helperResult = 168 + 100 = 268
            // With per-frame localsReady(), 'total' in the intermediate frame
            // complexMethod IS correctly restored to 84.
            // So total = 84 + helperResult = 84 + 268 = 352.
            java.util.List<String> userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.Arrays.asList(
                    "HELPER_AFTER a=168 afterFreeze=268",
                    "SB_AFTER=try-helper",
                    "AFTER_FREEZE total=352",
                    "COMPLEX_RESULT=352"),
                    userLines,
                    "Restore should preserve state across complex control flow. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    // ---------------------------------------------------------------
    // Deep-state tests: complex state at many stack levels
    // ---------------------------------------------------------------

    @Test
    @DisplayName("E2E: Deep state across 8 stack levels with mixed types")
    void deepStateFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-deepstate-", ".bin");
        try {
            // Step 1: Freeze at level 7 (8 levels deep)
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.DeepStateFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== DEEP STATE FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== DEEP STATE FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Deep state freeze should complete. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("BEFORE_FREEZE depth=7"),
                    "Should freeze at depth 7. Stdout:\n" + freezeResult.stdout());
            assertFalse(freezeResult.stdout().contains("FINAL_RESULT"),
                    "Original thread should not produce result");
            assertTrue(Files.size(snapshotFile) > 100, "Snapshot file should have content");

            // Step 2: Restore
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== DEEP STATE RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== DEEP STATE RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertRestoreSucceeded(restoreResult);

            // Verify state at every level
            String out = restoreResult.stdout();

            // Level 7 (deepest): depth=7, beforeFreeze=7000, afterFreeze=7042
            assertTrue(out.contains("L7_BEFORE=7000"), "L7 before freeze. Out:\n" + out);
            assertTrue(out.contains("L7_AFTER=7042"), "L7 after freeze. Out:\n" + out);
            assertTrue(out.contains("L7_STR=hello-L1-L2-L3-L4-L5-L6-L7"), "L7 string chain. Out:\n" + out);

            // Level 6: pi, e, product
            assertTrue(out.contains("L6_PI=3.14159265358979"), "L6 pi. Out:\n" + out);
            assertTrue(out.contains("L6_E=2.71828182845905"), "L6 e. Out:\n" + out);
            assertTrue(out.contains("L6_STR=hello-L1-L2-L3-L4-L5-L6"), "L6 string. Out:\n" + out);

            // Level 5: primes, sum=41
            assertTrue(out.contains("L5_PRIMES=[2, 3, 5, 7, 11, 13]"), "L5 primes. Out:\n" + out);
            assertTrue(out.contains("L5_SUM=41"), "L5 sum. Out:\n" + out);

            // Level 4: char, short, byte
            assertTrue(out.contains("L4_CHAR=E"), "L4 char. Out:\n" + out);
            assertTrue(out.contains("L4_SHORT=44"), "L4 short. Out:\n" + out);
            assertTrue(out.contains("L4_BYTE=4"), "L4 byte. Out:\n" + out);

            // Level 3: computed a/b/c from primitive locals + HashMap values
            assertTrue(out.contains("L3_ABC=21,103,124"), "L3 abc. Out:\n" + out);
            assertTrue(out.contains("L3_MAP=21,103,124"), "L3 map with boxed Integer values. Out:\n" + out);

            // Level 2: long, float, bool
            assertTrue(out.contains("L2_LONG=200000"), "L2 long. Out:\n" + out);
            assertTrue(out.contains("L2_FLOAT=5.0"), "L2 float. Out:\n" + out);
            assertTrue(out.contains("L2_BOOL=true"), "L2 bool. Out:\n" + out);

            // Level 1: int, double, array
            assertTrue(out.contains("L1_INT=10"), "L1 int. Out:\n" + out);
            assertTrue(out.contains("L1_DOUBLE=3.14"), "L1 double. Out:\n" + out);
            assertTrue(out.contains("L1_ARR=[1, 2, 3]"), "L1 array. Out:\n" + out);

            // Accumulator should have all levels
            assertTrue(out.contains("ACCUMULATOR=[root, L1, L2, L3, L4, L5, L6, L7]"),
                    "Accumulator should have all levels. Out:\n" + out);

            // Restored output must NOT contain pre-freeze output
            assertFalse(out.contains("BEFORE_FREEZE"),
                    "Restore must not replay pre-freeze output. Out:\n" + out);
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Immutable JDK types survive freeze/restore (BigDecimal, UUID, java.time, URI)")
    void immutableTypesFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-immutables-", ".bin");
        try {
            // Step 1: Freeze with immutable type locals
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.ImmutableTypesFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== IMMUTABLE TYPES FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== IMMUTABLE TYPES FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Immutable types freeze should complete. Stdout:\n" + freezeResult.stdout());
            assertTrue(freezeResult.stdout().contains("BEFORE_FREEZE"),
                    "Should print before freeze. Stdout:\n" + freezeResult.stdout());
            assertFalse(freezeResult.stdout().contains("PRICE="),
                    "Original thread should not continue past freeze");
            assertTrue(Files.size(snapshotFile) > 100, "Snapshot file should have content");

            // Step 2: Restore in a NEW JVM
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== IMMUTABLE TYPES RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== IMMUTABLE TYPES RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertRestoreSucceeded(restoreResult);
            String out = restoreResult.stdout();

            // BigDecimal / BigInteger
            assertTrue(out.contains("PRICE=199.99"), "BigDecimal price. Out:\n" + out);
            assertTrue(out.contains("TAX=0.0825"), "BigDecimal tax. Out:\n" + out);
            assertTrue(out.contains("BIGNUM=123456789012345678901234567890"),
                    "BigInteger. Out:\n" + out);

            // Verify BigDecimal arithmetic still works after restore
            assertTrue(out.contains("TOTAL=216.489175"),
                    "BigDecimal arithmetic after restore. Out:\n" + out);

            // UUID
            assertTrue(out.contains("UUID=550e8400-e29b-41d4-a716-446655440000"),
                    "UUID. Out:\n" + out);

            // java.time types
            assertTrue(out.contains("DATE=2025-06-15"), "LocalDate. Out:\n" + out);
            assertTrue(out.contains("TIME=14:30:45.123456789"), "LocalTime. Out:\n" + out);
            assertTrue(out.contains("DATETIME=2025-06-15T14:30:45.123456789"),
                    "LocalDateTime. Out:\n" + out);
            assertTrue(out.contains("INSTANT="), "Instant present. Out:\n" + out);
            assertTrue(out.contains("DURATION=PT2H30M15S"), "Duration. Out:\n" + out);
            assertTrue(out.contains("PERIOD=P1Y6M15D"), "Period. Out:\n" + out);

            // ZonedDateTime — verify it contains the zone
            assertTrue(out.contains("ZONED=") && out.contains("America/New_York"),
                    "ZonedDateTime with timezone. Out:\n" + out);

            // URI
            assertTrue(out.contains("URI=https://example.com/api/v1/resource?id=42"),
                    "URI. Out:\n" + out);

            // Collections containing immutables
            assertTrue(out.contains("MAP_ITEM1=29.99"),
                    "BigDecimal in HashMap. Out:\n" + out);
            assertTrue(out.contains("MAP_ITEM2=49.50"),
                    "BigDecimal in HashMap. Out:\n" + out);
            assertTrue(out.contains("UUIDS=2,11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222"),
                    "UUIDs in ArrayList. Out:\n" + out);

            // Marker from inner frame
            assertTrue(out.contains("MARKER=42"),
                    "Inner frame local. Out:\n" + out);

            // Restored output must NOT contain pre-freeze output
            assertFalse(out.contains("BEFORE_FREEZE"),
                    "Restore must not replay pre-freeze output. Out:\n" + out);
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Enum types survive freeze/restore with correct identity (==)")
    void enumFreezeAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-enum-", ".bin");
        try {
            // Step 1: Freeze with enum locals
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.EnumFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== ENUM FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().trim().isEmpty()) {
                System.out.println("=== ENUM FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    "Enum freeze should complete. Stdout:\n" + freezeResult.stdout());
            assertFalse(freezeResult.stdout().contains("COLOR="),
                    "Original thread should not continue past freeze");
            assertTrue(Files.size(snapshotFile) > 100, "Snapshot file should have content");

            // Step 2: Restore in a NEW JVM
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== ENUM RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().trim().isEmpty()) {
                System.out.println("=== ENUM RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertRestoreSucceeded(restoreResult);
            String out = restoreResult.stdout();

            // Identity checks (==) — the most critical enum semantic
            assertTrue(out.contains("COLOR_IS_RED=true"),
                    "Enum == identity for Color.RED. Out:\n" + out);
            assertTrue(out.contains("DIR_IS_EAST=true"),
                    "Enum == identity for Direction.EAST. Out:\n" + out);
            assertTrue(out.contains("UNIT_IS_SECONDS=true"),
                    "Enum == identity for TimeUnit.SECONDS. Out:\n" + out);

            // Values
            assertTrue(out.contains("COLOR=RED"), "Color name. Out:\n" + out);
            assertTrue(out.contains("COLOR_TEMP=warm"), "Custom enum field. Out:\n" + out);
            assertTrue(out.contains("COLOR_ORDINAL=0"), "Enum ordinal. Out:\n" + out);
            assertTrue(out.contains("DIR=EAST"), "Direction name. Out:\n" + out);
            assertTrue(out.contains("UNIT=SECONDS"), "TimeUnit name. Out:\n" + out);

            // Switch statement (depends on enum identity)
            assertTrue(out.contains("SWITCH=matched-red"),
                    "Switch on enum should work. Out:\n" + out);

            // Enum keys in HashMap
            assertTrue(out.contains("MAP_RED=5"), "Map with enum key RED. Out:\n" + out);
            assertTrue(out.contains("MAP_GREEN=3"), "Map with enum key GREEN. Out:\n" + out);
            assertTrue(out.contains("MAP_BLUE=7"), "Map with enum key BLUE. Out:\n" + out);

            // Enum values in HashSet
            assertTrue(out.contains("SET_CONTAINS_NORTH=true"), "Set contains NORTH. Out:\n" + out);
            assertTrue(out.contains("SET_CONTAINS_EAST=true"), "Set contains EAST. Out:\n" + out);
            assertTrue(out.contains("SET_CONTAINS_SOUTH=false"), "Set does not contain SOUTH. Out:\n" + out);

            // No pre-freeze output
            assertFalse(out.contains("BEFORE_FREEZE"),
                    "Restore must not replay pre-freeze output. Out:\n" + out);
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    @DisplayName("E2E: Freeze fails fast on unsupported types (Optional)")
    void unsupportedTypeFailsFast() throws Exception {
        Path snapshotFile = Files.createTempFile("durable-unsupported-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.UnsupportedTypeFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== UNSUPPORTED TYPE STDOUT ===\n" + result.stdout());
            if (!result.stderr().trim().isEmpty()) {
                System.out.println("=== UNSUPPORTED TYPE STDERR ===\n" + result.stderr());
            }

            // Freeze should NOT succeed
            assertFalse(result.stdout().contains("FREEZE_COMPLETE"),
                    "Freeze must not succeed with unsupported type. Stdout:\n" + result.stdout());

            // Should NOT continue past freeze (no AFTER_FREEZE output)
            assertFalse(result.stdout().contains("AFTER_FREEZE"),
                    "Thread must not continue past failed freeze. Stdout:\n" + result.stdout());

            // The exception should mention the unsupported type and be an UncapturableTypeException
            String allOutput = result.stdout() + result.stderr();
            assertTrue(allOutput.contains("UncapturableTypeException")
                            || allOutput.contains("java.util.Optional"),
                    "Error should mention the unsupported type. Output:\n" + allOutput);

            // Snapshot file should be empty or not written
            assertTrue(!Files.exists(snapshotFile) || Files.size(snapshotFile) == 0,
                    "Snapshot file should not be written on failure");
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }
}
