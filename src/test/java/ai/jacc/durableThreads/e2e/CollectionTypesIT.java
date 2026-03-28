package ai.jacc.durableThreads.e2e;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: freeze and restore a thread containing LinkedList, TreeSet,
 * and ArrayDeque in local variables. Verifies that collection contents
 * survive the freeze/restore round-trip.
 *
 * <p>These collection types use different internal data structures than
 * ArrayList and HashMap, which are the only collections currently tested.
 * LinkedList uses a doubly-linked node chain, TreeSet wraps a TreeMap
 * (NavigableMap), and ArrayDeque uses a circular array buffer.</p>
 */
@Tag("e2e")
class CollectionTypesIT {

    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        classpath = ChildJvm.buildClasspath();
    }

    @Test
    void linkedListSurvivesFreezeRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("collection-types-", ".dat");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.CollectionTypesFreezeProgram",
                    classpath, port,
                    new String[]{snapshotFile.toString()}, 60);

            assertEquals(0, result.exitCode(),
                    "Child JVM should exit cleanly. Stderr:\n" + result.stderr());

            assertTrue(result.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + result.stdout()
                    + "\nStderr:\n" + result.stderr());

            // Verify LinkedList contents after restore
            assertTrue(result.stdout().contains("LinkedList.size=3"),
                    "LinkedList should have 3 elements after restore. Stdout:\n"
                    + result.stdout());
            assertTrue(result.stdout().contains("LinkedList=[alpha, beta, gamma]"),
                    "LinkedList should contain [alpha, beta, gamma] after restore. Stdout:\n"
                    + result.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    void treeSetSurvivesFreezeRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("collection-types-", ".dat");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.CollectionTypesFreezeProgram",
                    classpath, port,
                    new String[]{snapshotFile.toString()}, 60);

            assertEquals(0, result.exitCode(),
                    "Child JVM should exit cleanly. Stderr:\n" + result.stderr());

            assertTrue(result.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + result.stdout()
                    + "\nStderr:\n" + result.stderr());

            // TreeSet should maintain sorted order
            assertTrue(result.stdout().contains("TreeSet.size=3"),
                    "TreeSet should have 3 elements after restore. Stdout:\n"
                    + result.stdout());
            assertTrue(result.stdout().contains("TreeSet=[apple, banana, cherry]"),
                    "TreeSet should contain sorted [apple, banana, cherry] after restore. Stdout:\n"
                    + result.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @Test
    void arrayDequeSurvivesFreezeRestore() throws Exception {
        Path snapshotFile = Files.createTempFile("collection-types-", ".dat");
        try {
            int port = ChildJvm.findFreePort();
            ChildJvm.Result result = ChildJvm.run(
                    "ai.jacc.durableThreads.e2e.CollectionTypesFreezeProgram",
                    classpath, port,
                    new String[]{snapshotFile.toString()}, 60);

            assertEquals(0, result.exitCode(),
                    "Child JVM should exit cleanly. Stderr:\n" + result.stderr());

            assertTrue(result.stdout().contains("RESTORE_COMPLETE"),
                    "Restore should complete. Stdout:\n" + result.stdout()
                    + "\nStderr:\n" + result.stderr());

            // ArrayDeque should maintain insertion order
            assertTrue(result.stdout().contains("ArrayDeque.size=3"),
                    "ArrayDeque should have 3 elements after restore. Stdout:\n"
                    + result.stdout());
            assertTrue(result.stdout().contains("ArrayDeque=[first, second, third]"),
                    "ArrayDeque should contain [first, second, third] after restore. Stdout:\n"
                    + result.stdout());
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }
}
