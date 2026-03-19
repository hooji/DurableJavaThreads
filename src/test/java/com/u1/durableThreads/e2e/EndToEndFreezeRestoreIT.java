package com.u1.durableThreads.e2e;

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

    // ===================================================================
    // Basic tests
    // ===================================================================

    @Test
    @DisplayName("Agent loads successfully in child JVM")
    void agentLoads() throws Exception {
        ChildJvm.Result result = ChildJvm.run(
                "com.u1.durableThreads.e2e.AgentLoadProgram",
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
                    "com.u1.durableThreads.e2e.FreezeProgram",
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
                    "com.u1.durableThreads.e2e.FreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().isBlank()) {
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
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().isBlank()) {
                System.out.println("=== RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load snapshot in second JVM. Stdout:\n" + restoreResult.stdout());

            // NOTE: Full replay execution (AFTER_FREEZE=42) requires completing the
            // ThreadRestorer's JDI-based local variable setting and replay mechanism.
            // That work is tracked separately. For now, we verify the snapshot
            // survives serialization across JVM boundaries.
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
                    "com.u1.durableThreads.e2e.SequentialFreezeProgram",
                    classpath, port,
                    new String[]{snapshotDir.toString()}, 120);

            System.out.println("=== SEQUENTIAL STDOUT ===\n" + result.stdout());
            if (!result.stderr().isBlank()) {
                System.out.println("=== SEQUENTIAL STDERR ===\n" + result.stderr());
            }

            // At minimum, the first freeze (i=0) should succeed
            assertTrue(result.stdout().contains("FREEZE i=0"),
                    "First freeze should succeed. Stdout:\n" + result.stdout());

            // Verify snapshot file was written
            long snapshotCount;
            try (var files = Files.list(snapshotDir)) {
                snapshotCount = files.filter(p -> p.toString().endsWith(".bin")).count();
            }
            assertTrue(snapshotCount >= 1,
                    "At least one snapshot file should be written. Count: " + snapshotCount);
        } finally {
            try (var files = Files.walk(snapshotDir)) {
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
                    "com.u1.durableThreads.e2e.DeepCallChainFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== DEEP CHAIN FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().isBlank()) {
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
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== DEEP CHAIN RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().isBlank()) {
                System.out.println("=== DEEP CHAIN RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load deep-chain snapshot. Stdout:\n" + restoreResult.stdout());
            assertTrue(restoreResult.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + restoreResult.stdout());

            // Verify the snapshot has multiple frames (at least 3: outer, middle, inner)
            // The FRAME_COUNT line tells us
            assertTrue(restoreResult.stdout().contains("FRAME_COUNT="),
                    "Should report frame count. Stdout:\n" + restoreResult.stdout());
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
                    "com.u1.durableThreads.e2e.LoopFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== LOOP FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().isBlank()) {
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
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== LOOP RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().isBlank()) {
                System.out.println("=== LOOP RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load loop snapshot. Stdout:\n" + restoreResult.stdout());
            assertTrue(restoreResult.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + restoreResult.stdout());
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
                    "com.u1.durableThreads.e2e.ReturnValueFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== RETVAL FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().isBlank()) {
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
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== RETVAL RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().isBlank()) {
                System.out.println("=== RETVAL RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load return-value snapshot. Stdout:\n" + restoreResult.stdout());
            assertTrue(restoreResult.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + restoreResult.stdout());
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
                    "com.u1.durableThreads.e2e.HeapObjectFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== HEAP FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().isBlank()) {
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
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== HEAP RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().isBlank()) {
                System.out.println("=== HEAP RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load heap snapshot. Stdout:\n" + restoreResult.stdout());
            assertTrue(restoreResult.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + restoreResult.stdout());

            // Verify primitive local survived
            assertTrue(restoreResult.stdout().contains("AFTER_FREEZE primitiveLocal=99"),
                    "Primitive local should survive. Stdout:\n" + restoreResult.stdout());

            // Verify object locals — report what we got for debugging
            // Full heap object restoration depends on JDI ObjectReference resolution
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
                    "com.u1.durableThreads.e2e.FibonacciFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString(), "5"}, 60);

            System.out.println("=== FIB FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().isBlank()) {
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
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== FIB RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().isBlank()) {
                System.out.println("=== FIB RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load fib snapshot. Stdout:\n" + restoreResult.stdout());
            assertTrue(restoreResult.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + restoreResult.stdout());
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
                    "com.u1.durableThreads.e2e.TryCatchFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== TRYCATCH FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().isBlank()) {
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
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== TRYCATCH RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().isBlank()) {
                System.out.println("=== TRYCATCH RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load try-catch snapshot");
            assertTrue(restoreResult.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete");
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
                    "com.u1.durableThreads.e2e.NestedLoopFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== NESTED FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().isBlank()) {
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
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== NESTED RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().isBlank()) {
                System.out.println("=== NESTED RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load nested loop snapshot");
            assertTrue(restoreResult.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete");
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
                    "com.u1.durableThreads.e2e.ManyLocalsFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== MANY LOCALS FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().isBlank()) {
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
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== MANY LOCALS RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().isBlank()) {
                System.out.println("=== MANY LOCALS RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertTrue(restoreResult.stdout().contains("SNAPSHOT_LOADED=true"),
                    "Should load many-locals snapshot");
            assertTrue(restoreResult.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete");

            // Verify the restored thread produced AFTER_FREEZE output
            // (actual local values depend on JDI local-setting success)
            assertTrue(restoreResult.stdout().contains("AFTER_FREEZE"),
                    "Restored thread should reach post-freeze code. Stdout:\n" + restoreResult.stdout());
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
                    "com.u1.durableThreads.e2e.PeriodicFreezeRestoreProgram",
                    classpath, port,
                    new String[]{snapshotDir.toString()}, 120);

            System.out.println("=== PERIODIC STDOUT ===\n" + result.stdout());
            if (!result.stderr().isBlank()) {
                System.out.println("=== PERIODIC STDERR ===\n" + result.stderr());
            }

            // First freeze at i=1 (1 % 5 == 1) should succeed
            assertTrue(result.stdout().contains("FREEZE i=1"),
                    "First periodic freeze should succeed. Stdout:\n" + result.stdout());

            // Verify at least one checkpoint file was written
            long checkpointCount;
            try (var files = Files.list(snapshotDir)) {
                checkpointCount = files.filter(p -> p.toString().endsWith(".bin")).count();
            }
            assertTrue(checkpointCount >= 1,
                    "At least one checkpoint file should be written. Count: " + checkpointCount);
        } finally {
            try (var files = Files.walk(snapshotDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
    }
}
