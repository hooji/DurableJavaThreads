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
            assertTrue(restoreResult.stdout().contains("AFTER_FREEZE=42"),
                    "Rep " + info.getCurrentRepetition() + ": counter should be 42. Stdout:\n"
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
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }
}
