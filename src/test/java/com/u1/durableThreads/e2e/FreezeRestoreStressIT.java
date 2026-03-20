package com.u1.durableThreads.e2e;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress tests that repeat freeze/restore cycles multiple times to detect
 * intermittent failures caused by race conditions or timing issues.
 *
 * <p>These tests run each scenario 20 times. Intermittent failures that
 * occur even 5% of the time should be caught reliably (probability of
 * missing: 0.95^20 = 36%, so ~64% chance of catching per CI run).
 * At 30 repetitions: 0.95^30 = 21% miss rate.</p>
 *
 * <p>Run with: {@code mvn failsafe:integration-test -Dgroups=e2e}</p>
 *
 * <p>Requires: {@code mvn package -DskipTests} to build the agent jar first.</p>
 */
@Tag("e2e")
class FreezeRestoreStressIT {

    private static final int REPETITIONS = 20;

    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        classpath = ChildJvm.buildClasspath();
    }

    private static void assertFreezeSucceeded(ChildJvm.Result result) {
        assertTrue(result.stdout().contains("FREEZE_COMPLETE"),
                "Freeze should complete. Stdout:\n" + result.stdout()
                        + "\nStderr:\n" + result.stderr());
    }

    private static void assertRestoreSucceeded(ChildJvm.Result result) {
        assertFalse(result.stdout().contains("RESTORE_FAILED"),
                "Restored thread threw an exception. Stderr:\n" + result.stderr());
        assertTrue(result.stdout().contains("RESTORE_COMPLETE"),
                "Restore should complete. Stdout:\n" + result.stdout()
                        + "\nStderr:\n" + result.stderr());
    }

    /**
     * Extract the "user output" lines from restore stdout — lines printed by
     * the restored thread, excluding the RestoreProgram infrastructure lines.
     */
    private static java.util.List<String> extractUserOutput(String stdout) {
        return stdout.lines()
                .filter(line -> !line.startsWith("SNAPSHOT_LOADED="))
                .filter(line -> !line.startsWith("FRAME_COUNT="))
                .filter(line -> !line.equals("RESTORE_COMPLETE"))
                .filter(line -> !line.startsWith("Listening for transport dt_socket"))
                .filter(line -> !line.isBlank())
                .toList();
    }

    // ===================================================================
    // Basic freeze/restore — most likely to expose JDI timing races
    // ===================================================================

    @RepeatedTest(value = REPETITIONS, name = "basic freeze/restore [{currentRepetition}/{totalRepetitions}]")
    @DisplayName("Stress: basic freeze and restore")
    void basicFreezeRestore(RepetitionInfo info) throws Exception {
        Path snapshotFile = Files.createTempFile("stress-basic-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.FreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            assertFreezeSucceeded(freezeResult);
            assertTrue(Files.size(snapshotFile) > 100);

            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            assertRestoreSucceeded(restoreResult);

            // Exact output: only post-freeze lines, no replay
            var userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.List.of("AFTER_FREEZE=42", "MESSAGE=hello-from-freeze"),
                    userLines,
                    "Rep " + info.getCurrentRepetition()
                            + ": restore should produce only post-freeze lines. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    // ===================================================================
    // Deep call chain — stresses multi-frame JDI local setting
    // ===================================================================

    @RepeatedTest(value = REPETITIONS, name = "deep chain [{currentRepetition}/{totalRepetitions}]")
    @DisplayName("Stress: deep call chain freeze and restore")
    void deepChainFreezeRestore(RepetitionInfo info) throws Exception {
        Path snapshotFile = Files.createTempFile("stress-deep-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.DeepCallChainFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            assertFreezeSucceeded(freezeResult);

            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            assertRestoreSucceeded(restoreResult);

            // Exact output check for deep chain
            var userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.List.of(
                    "INNER_AFTER=117", "MIDDLE_AFTER=117",
                    "OUTER_AFTER=217", "DEEP_RESULT=1217"),
                    userLines,
                    "Rep " + info.getCurrentRepetition()
                            + ": deep chain restore should produce only post-freeze lines. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    // ===================================================================
    // Loop freeze — stresses loop counter local restoration
    // ===================================================================

    @RepeatedTest(value = REPETITIONS, name = "loop freeze [{currentRepetition}/{totalRepetitions}]")
    @DisplayName("Stress: loop freeze and restore")
    void loopFreezeRestore(RepetitionInfo info) throws Exception {
        Path snapshotFile = Files.createTempFile("stress-loop-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.LoopFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            assertFreezeSucceeded(freezeResult);

            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            assertRestoreSucceeded(restoreResult);

            // Exact output check for loop
            var userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.List.of(
                    "AFTER_FREEZE i=4 sum=10",
                    "ITERATION i=5 sum=15", "ITERATION i=6 sum=21",
                    "ITERATION i=7 sum=28", "ITERATION i=8 sum=36",
                    "ITERATION i=9 sum=45",
                    "FINAL_SUM=45", "LOOP_RESULT=45"),
                    userLines,
                    "Rep " + info.getCurrentRepetition()
                            + ": loop restore should continue from i=5, no replay. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    // ===================================================================
    // Heap objects — stresses JDI object reference resolution
    // ===================================================================

    @RepeatedTest(value = REPETITIONS, name = "heap objects [{currentRepetition}/{totalRepetitions}]")
    @DisplayName("Stress: heap object freeze and restore")
    void heapObjectFreezeRestore(RepetitionInfo info) throws Exception {
        Path snapshotFile = Files.createTempFile("stress-heap-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.HeapObjectFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            assertFreezeSucceeded(freezeResult);

            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            assertRestoreSucceeded(restoreResult);
            assertTrue(restoreResult.stdout().contains("AFTER_FREEZE primitiveLocal=99"),
                    "Rep " + info.getCurrentRepetition() + ": primitiveLocal should be 99. Stdout:\n"
                            + restoreResult.stdout());

            // Restored thread must NOT replay pre-freeze output
            assertFalse(restoreResult.stdout().contains("BEFORE_FREEZE"),
                    "Rep " + info.getCurrentRepetition()
                            + ": restore must not replay pre-freeze. Stdout:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    // ===================================================================
    // Return value preservation — stresses pre-freeze local survival
    // ===================================================================

    @RepeatedTest(value = REPETITIONS, name = "return values [{currentRepetition}/{totalRepetitions}]")
    @DisplayName("Stress: return value freeze and restore")
    void returnValueFreezeRestore(RepetitionInfo info) throws Exception {
        Path snapshotFile = Files.createTempFile("stress-retval-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.ReturnValueFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            assertFreezeSucceeded(freezeResult);

            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.RestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            assertRestoreSucceeded(restoreResult);

            // Exact output check for return values
            var userLines = extractUserOutput(restoreResult.stdout());
            assertEquals(java.util.List.of(
                    "AFTER_FREEZE v1=15 v2=45 v3=43",
                    "AFTER_COMPUTE v4=21 v5=121",
                    "CHAIN_RESULT=245"),
                    userLines,
                    "Rep " + info.getCurrentRepetition()
                            + ": return value restore should produce only post-freeze lines. Got:\n"
                            + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }
}
