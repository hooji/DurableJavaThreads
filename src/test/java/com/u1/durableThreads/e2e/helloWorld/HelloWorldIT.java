package com.u1.durableThreads.e2e.helloWorld;

import com.u1.durableThreads.e2e.ChildJvm;
import org.junit.jupiter.api.*;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quick Start "Hello World" integration test.
 *
 * <p>Freezes a thread inside a {@code for} loop at {@code i == 5}, then
 * restores it in a new JVM and verifies the loop resumes from where it
 * left off.</p>
 */
@Tag("e2e")
class HelloWorldIT {

    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        classpath = ChildJvm.buildClasspath();
    }

    @Test
    @DisplayName("Hello World: freeze at i==5, restore and continue to 10")
    void freezeAtFiveAndRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("hello-world-", ".bin");
        try {
            // --- Freeze ---
            int freezePort = ChildJvm.findFreePort();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.helloWorld.HelloFreezeProgram",
                    classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== HELLO FREEZE STDOUT ===\n" + freezeResult.stdout());
            if (!freezeResult.stderr().isBlank()) {
                System.out.println("=== HELLO FREEZE STDERR ===\n" + freezeResult.stderr());
            }

            // Should print i=0 through i=5, then freeze
            for (int i = 0; i <= 5; i++) {
                assertTrue(freezeResult.stdout().contains("i=" + i),
                        "Should print i=" + i + " before freeze. Stdout:\n" + freezeResult.stdout());
            }

            // Original thread must NOT continue past freeze
            assertFalse(freezeResult.stdout().contains("RESUMED"),
                    "Original thread should not resume. Stdout:\n" + freezeResult.stdout());
            assertFalse(freezeResult.stdout().contains("DONE"),
                    "Original thread should not complete. Stdout:\n" + freezeResult.stdout());
            assertFalse(freezeResult.stdout().contains("i=6"),
                    "Original thread should not reach i=6. Stdout:\n" + freezeResult.stdout());

            assertTrue(Files.size(snapshotFile) > 100,
                    "Snapshot file should have content");

            // --- Restore ---
            int restorePort = ChildJvm.findFreePort();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    "com.u1.durableThreads.e2e.helloWorld.HelloRestoreProgram",
                    classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);

            System.out.println("=== HELLO RESTORE STDOUT ===\n" + restoreResult.stdout());
            if (!restoreResult.stderr().isBlank()) {
                System.out.println("=== HELLO RESTORE STDERR ===\n" + restoreResult.stderr());
            }

            assertFalse(restoreResult.stdout().contains("RESTORE_FAILED"),
                    "Restore should not fail. Stderr:\n" + restoreResult.stderr());
            assertTrue(restoreResult.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + restoreResult.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }
}
