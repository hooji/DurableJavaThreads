package ai.jacc.durableThreads.e2e;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmarks for freeze/restore operations.
 *
 * <p>Measures and reports:</p>
 * <ul>
 *   <li>Freeze latency (wall-clock time for the freeze child JVM)</li>
 *   <li>Restore latency (wall-clock time for the restore child JVM)</li>
 *   <li>Snapshot file size</li>
 *   <li>Heap object count</li>
 * </ul>
 *
 * <p>Run with: {@code mvn failsafe:integration-test -Dit.test=PerformanceBenchmarkIT}</p>
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerformanceBenchmarkIT {

    private static String classpath;
    private static final List<BenchmarkResult> results = new ArrayList<>();

    @BeforeAll
    static void setup() {
        classpath = ChildJvm.buildClasspath();
    }

    @AfterAll
    static void printSummary() {
        if (results.isEmpty()) return;

        System.out.println();
        System.out.println("=== PERFORMANCE BENCHMARK RESULTS ===");
        System.out.printf("%-30s %10s %12s %14s %10s%n",
                "Scenario", "Freeze ms", "Restore ms", "Snapshot bytes", "Heap objs");
        System.out.println("-".repeat(80));
        for (BenchmarkResult r : results) {
            System.out.printf("%-30s %10d %12d %14d %10s%n",
                    r.scenario, r.freezeMs, r.restoreMs, r.snapshotBytes, r.heapSize);
        }
        System.out.println("=".repeat(80));
        System.out.println();
    }

    record BenchmarkResult(String scenario, long freezeMs, long restoreMs,
                            long snapshotBytes, String heapSize) {}

    @Test @Order(1)
    void benchmarkBasicFreezeRestore() throws Exception {
        runBenchmark("basic", "ai.jacc.durableThreads.e2e.FreezeProgram",
                "ai.jacc.durableThreads.e2e.RestoreProgram");
    }

    @Test @Order(2)
    void benchmarkDeepCallChain() throws Exception {
        runBenchmark("deep-call-chain", "ai.jacc.durableThreads.e2e.DeepCallChainFreezeProgram",
                "ai.jacc.durableThreads.e2e.RestoreProgram");
    }

    @Test @Order(3)
    void benchmarkLoop() throws Exception {
        runBenchmark("loop", "ai.jacc.durableThreads.e2e.LoopFreezeProgram",
                "ai.jacc.durableThreads.e2e.RestoreProgram");
    }

    @Test @Order(4)
    void benchmarkHeapObjects() throws Exception {
        runBenchmark("heap-objects", "ai.jacc.durableThreads.e2e.HeapObjectFreezeProgram",
                "ai.jacc.durableThreads.e2e.RestoreProgram");
    }

    @Test @Order(5)
    void benchmarkReturnValues() throws Exception {
        runBenchmark("return-values", "ai.jacc.durableThreads.e2e.ReturnValueFreezeProgram",
                "ai.jacc.durableThreads.e2e.RestoreProgram");
    }

    @Test @Order(6)
    void benchmarkManyLocals() throws Exception {
        runBenchmark("many-locals", "ai.jacc.durableThreads.e2e.ManyLocalsFreezeProgram",
                "ai.jacc.durableThreads.e2e.RestoreProgram");
    }

    @Test @Order(7)
    void benchmarkNestedLoop() throws Exception {
        runBenchmark("nested-loop", "ai.jacc.durableThreads.e2e.NestedLoopFreezeProgram",
                "ai.jacc.durableThreads.e2e.RestoreProgram");
    }

    private void runBenchmark(String scenario, String freezeClass, String restoreClass)
            throws Exception {
        Path snapshotFile = Files.createTempFile("bench-" + scenario + "-", ".bin");
        try {
            int freezePort = ChildJvm.findFreePort();

            // --- Freeze ---
            long freezeStart = System.currentTimeMillis();
            ChildJvm.Result freezeResult = ChildJvm.run(
                    freezeClass, classpath, freezePort,
                    new String[]{snapshotFile.toString()}, 60);
            long freezeMs = System.currentTimeMillis() - freezeStart;

            assertTrue(freezeResult.stdout().contains("FREEZE_COMPLETE"),
                    scenario + " freeze failed. Stdout:\n" + freezeResult.stdout()
                            + "\nStderr:\n" + freezeResult.stderr());

            long snapshotBytes = Files.size(snapshotFile);

            // Extract heap size from freeze output
            String heapSize = "?";
            Matcher m = Pattern.compile("HEAP_SIZE=(\\d+)").matcher(freezeResult.stdout());
            if (m.find()) heapSize = m.group(1);

            // --- Restore ---
            int restorePort = ChildJvm.findFreePort();
            long restoreStart = System.currentTimeMillis();
            ChildJvm.Result restoreResult = ChildJvm.run(
                    restoreClass, classpath, restorePort,
                    new String[]{snapshotFile.toString()}, 60);
            long restoreMs = System.currentTimeMillis() - restoreStart;

            assertFalse(restoreResult.stdout().contains("RESTORE_FAILED"),
                    scenario + " restore failed. Stderr:\n" + restoreResult.stderr());
            assertTrue(restoreResult.stdout().contains("RESTORE_COMPLETE"),
                    scenario + " restore incomplete. Stdout:\n" + restoreResult.stdout()
                            + "\nStderr:\n" + restoreResult.stderr());

            results.add(new BenchmarkResult(scenario, freezeMs, restoreMs, snapshotBytes, heapSize));

        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }
}
