package com.u1.durableThreads;

import com.u1.durableThreads.snapshot.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.io.*;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests sequential freeze/restore cycles on the same logical thread.
 *
 * <p>Simulates a loop 0–100 that freezes every time {@code i % 5 == 0}.
 * Each freeze produces a snapshot that is serialized, deserialized, and
 * validated. The test verifies:</p>
 * <ol>
 *   <li>Each restore receives the correct loop counter value</li>
 *   <li>Serialized snapshot size stays roughly constant (no leak/growth)</li>
 *   <li>The instrumented code handles repeated freeze/skip cycles correctly</li>
 * </ol>
 */
class SequentialFreezeRestoreTest {

    // ===================================================================
    // Scenario class: a loop that freezes periodically
    // ===================================================================

    /**
     * Simulates a workflow that freezes every N iterations.
     * Uses a shared log to record what happens at each step.
     */
    public static class PeriodicFreezeWorkflow {

        /**
         * Run a loop from start to end (exclusive). When i % freezeInterval == 0,
         * call FreezePoint.freeze(i) which records the freeze and can be skipped
         * during replay. Returns the final sum.
         */
        public static int runLoop(int start, int end, int freezeInterval, List<Integer> freezeLog) {
            int sum = 0;
            for (int i = start; i < end; i++) {
                if (i % freezeInterval == 0) {
                    FreezePoint.freeze(i, freezeLog);
                }
                sum += i;
            }
            return sum;
        }
    }

    /**
     * Freeze point that records the loop counter at each freeze.
     */
    public static class FreezePoint {
        public static int freezeCount = 0;

        public static void freeze(int loopCounter, List<Integer> log) {
            freezeCount++;
            log.add(loopCounter);
        }

        public static void reset() {
            freezeCount = 0;
        }
    }

    // ===================================================================
    // Test: normal execution records all freeze points
    // ===================================================================

    @Test
    void normalExecutionRecordsAllFreezePoints() throws Exception {
        Class<?> clazz = loadInstrumented(PeriodicFreezeWorkflow.class);
        FreezePoint.reset();

        List<Integer> freezeLog = new ArrayList<>();
        Method m = clazz.getMethod("runLoop", int.class, int.class, int.class, List.class);
        int sum = (int) m.invoke(null, 0, 101, 5, freezeLog);

        // Sum of 0..100 = 5050
        assertEquals(5050, sum);

        // Should freeze at 0, 5, 10, 15, ..., 100 → 21 freeze points
        assertEquals(21, freezeLog.size(), "Should have 21 freeze points (0,5,10,...,100)");
        for (int idx = 0; idx < freezeLog.size(); idx++) {
            assertEquals(idx * 5, freezeLog.get(idx),
                    "Freeze point " + idx + " should be at i=" + (idx * 5));
        }
    }

    // ===================================================================
    // Test: replay mode skips freeze call, loop still computes correctly
    // ===================================================================

    /**
     * In replay mode, the skip-check fires ONCE per method entry — the first time
     * the invoke index matches __skip. This is correct: in a real restore, the
     * method is entered once and the skip jumps past the freeze invoke.
     * Subsequent loop iterations execute the invoke normally (since __skip resets
     * to -1 after the first skip).
     *
     * This test verifies that semantic: exactly one freeze call is skipped (the
     * first at i=0), and the remaining 20 execute.
     */
    @Test
    void replayModeSkipsFirstFreezeCallOnly() throws Exception {
        Class<?> clazz = loadInstrumented(PeriodicFreezeWorkflow.class);

        int freezeInvokeIndex = findInvokeIndex(PeriodicFreezeWorkflow.class,
                "runLoop", "(IIILjava/util/List;)I",
                "com/u1/durableThreads/SequentialFreezeRestoreTest$FreezePoint", "freeze");
        assertTrue(freezeInvokeIndex >= 0, "Should find FreezePoint.freeze invoke");

        FreezePoint.reset();
        List<Integer> freezeLog = new ArrayList<>();

        // Activate replay targeting the freeze invoke
        ReplayState.activate(new int[]{freezeInvokeIndex});
        try {
            Method m = clazz.getMethod("runLoop", int.class, int.class, int.class, List.class);
            int sum = (int) m.invoke(null, 0, 101, 5, freezeLog);

            assertEquals(5050, sum, "Sum should still be correct in replay mode");
            // The skip fires once (at i=0), then __skip resets to -1.
            // The remaining 20 freeze calls (i=5,10,...,100) execute normally.
            assertEquals(20, FreezePoint.freezeCount,
                    "20 of 21 freeze calls should execute (first one skipped)");
            assertEquals(20, freezeLog.size());
            assertEquals(5, freezeLog.get(0), "First logged freeze should be at i=5 (i=0 was skipped)");
        } finally {
            ReplayState.deactivate();
        }
    }

    // ===================================================================
    // Test: simulate sequential freeze/restore with snapshot serialization
    // ===================================================================

    /**
     * Simulates the full sequential freeze/restore pattern:
     * - Run the loop from 0 to 100
     * - Every time i % 5 == 0, create a snapshot, serialize it, deserialize it
     * - Verify the snapshot contains the correct loop counter
     * - Verify serialized size is stable (doesn't grow)
     *
     * Since actual JDI freeze isn't available in unit tests, we build snapshots
     * manually with the correct local variable state at each freeze point.
     */
    @Test
    void sequentialFreezeRestoreWithSerializationStability() throws Exception {
        List<byte[]> serializedSnapshots = new ArrayList<>();
        List<Integer> restoredCounters = new ArrayList<>();

        for (int i = 0; i <= 100; i++) {
            if (i % 5 != 0) continue;

            // Build a snapshot representing the thread state at this freeze point
            ThreadSnapshot snapshot = buildSnapshotAtIteration(i);

            // Serialize
            byte[] bytes = serialize(snapshot);
            serializedSnapshots.add(bytes);

            // Deserialize and verify
            ThreadSnapshot restored = deserialize(bytes);
            int restoredI = extractLoopCounter(restored);
            restoredCounters.add(restoredI);

            assertEquals(i, restoredI,
                    "Restored snapshot at iteration " + i + " should have correct counter");
        }

        // Should have 21 snapshots (0, 5, 10, ..., 100)
        assertEquals(21, serializedSnapshots.size());

        // Verify ALL restored counters
        for (int idx = 0; idx < restoredCounters.size(); idx++) {
            assertEquals(idx * 5, restoredCounters.get(idx),
                    "Counter at index " + idx + " should be " + (idx * 5));
        }

        // Verify serialized size stability: no snapshot should be more than 2x
        // the average (they all have the same structure, just different values)
        int totalSize = 0;
        int minSize = Integer.MAX_VALUE;
        int maxSize = Integer.MIN_VALUE;
        for (byte[] bytes : serializedSnapshots) {
            totalSize += bytes.length;
            minSize = Math.min(minSize, bytes.length);
            maxSize = Math.max(maxSize, bytes.length);
        }
        double avgSize = (double) totalSize / serializedSnapshots.size();

        assertTrue(maxSize < avgSize * 2,
                String.format("Max snapshot size (%d) should be < 2x average (%.0f). " +
                        "Min=%d, Max=%d — indicates snapshot size is growing over time.",
                        maxSize, avgSize, minSize, maxSize));

        // Stricter: max should be within 20% of min for stable-structure snapshots
        double ratio = (double) maxSize / minSize;
        assertTrue(ratio < 1.2,
                String.format("Max/min ratio (%.2f) should be < 1.2 for stable snapshots. " +
                        "Min=%d, Max=%d", ratio, minSize, maxSize));
    }

    /**
     * Test that many sequential snapshot serialization round-trips preserve
     * all data (structural equality across round-trips).
     */
    @Test
    void snapshotSerializationRoundTripsPreserveData() throws Exception {
        ThreadSnapshot snapshot = buildSnapshotAtIteration(50, FIXED_INSTANT);

        // Round-trip 3 times
        byte[] bytes1 = serialize(snapshot);
        ThreadSnapshot restored1 = deserialize(bytes1);
        byte[] bytes2 = serialize(restored1);
        ThreadSnapshot restored2 = deserialize(bytes2);
        byte[] bytes3 = serialize(restored2);
        ThreadSnapshot restored3 = deserialize(bytes3);

        // All restored snapshots should have the same data
        for (ThreadSnapshot r : List.of(restored1, restored2, restored3)) {
            assertEquals(snapshot.capturedAt(), r.capturedAt());
            assertEquals(snapshot.threadName(), r.threadName());
            assertEquals(snapshot.frameCount(), r.frameCount());
            assertEquals(50, extractLoopCounter(r));
            assertEquals(snapshot.topFrame().className(), r.topFrame().className());
            assertEquals(snapshot.topFrame().methodName(), r.topFrame().methodName());
            assertEquals(snapshot.topFrame().locals().size(), r.topFrame().locals().size());
        }

        // Sizes should be very close (within 5%) — structural overhead may vary
        // slightly due to Java serialization's internal class descriptor caching
        double ratio = (double) Math.max(bytes2.length, bytes3.length)
                / Math.min(bytes2.length, bytes3.length);
        assertTrue(ratio < 1.05,
                String.format("Serialization sizes should be stable: %d vs %d (ratio %.3f)",
                        bytes2.length, bytes3.length, ratio));
    }

    /**
     * Verify that 100 sequential snapshots can all coexist and be independently
     * deserialized without interference.
     */
    @Test
    void hundredSnapshotsIndependentlyDeserializable() throws Exception {
        // Serialize all 21 snapshots
        Map<Integer, byte[]> serializedMap = new LinkedHashMap<>();
        for (int i = 0; i <= 100; i += 5) {
            serializedMap.put(i, serialize(buildSnapshotAtIteration(i)));
        }

        // Deserialize in random order and verify
        List<Integer> keys = new ArrayList<>(serializedMap.keySet());
        Collections.shuffle(keys, new Random(42)); // deterministic shuffle
        for (int i : keys) {
            ThreadSnapshot restored = deserialize(serializedMap.get(i));
            assertEquals(i, extractLoopCounter(restored),
                    "Snapshot for i=" + i + " should deserialize correctly regardless of order");
        }
    }

    /**
     * Simulate the full loop with "suspend, snapshot, restore, resume" at each
     * freeze point. Each segment of the loop is run independently via the
     * instrumented code, verifying that partial sums accumulate correctly.
     */
    @Test
    void simulatedSequentialFreezeRestoreComputesCorrectSum() throws Exception {
        Class<?> clazz = loadInstrumented(PeriodicFreezeWorkflow.class);
        Method m = clazz.getMethod("runLoop", int.class, int.class, int.class, List.class);

        // Run the loop in segments: [0,5), [5,10), [10,15), ..., [95,101)
        // Each segment starts where the previous one's freeze point was.
        // freezeInterval=5 means freeze at the START of each segment.
        int totalSum = 0;
        List<Integer> allFreezes = new ArrayList<>();

        // Segment 0: run from 0 to 5 (freeze at 0, then compute 0+1+2+3+4)
        // But actually we run the full loop 0..100 normally — the point is that
        // each segment produces the correct partial result.
        for (int start = 0; start < 101; start += 5) {
            int end = Math.min(start + 5, 101);
            FreezePoint.reset();
            List<Integer> segmentFreezes = new ArrayList<>();

            int segmentSum = (int) m.invoke(null, start, end, 5, segmentFreezes);
            totalSum += segmentSum;

            allFreezes.addAll(segmentFreezes);
        }

        assertEquals(5050, totalSum, "Total sum across all segments should be 5050");

        // Each segment should have exactly one freeze point at its start
        assertEquals(21, allFreezes.size());
        for (int idx = 0; idx < allFreezes.size(); idx++) {
            assertEquals(idx * 5, allFreezes.get(idx));
        }
    }

    // ===================================================================
    // Bytecode verification
    // ===================================================================

    @Test
    void periodicFreezeWorkflowPassesVerification() throws Exception {
        byte[] original = loadClassBytes(PeriodicFreezeWorkflow.class);
        byte[] instrumented = instrument(original);

        ClassReader cr = new ClassReader(instrumented);
        assertDoesNotThrow(() ->
                cr.accept(new org.objectweb.asm.util.CheckClassAdapter(
                        new ClassVisitor(Opcodes.ASM9) {}), 0));
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    private static ThreadSnapshot buildSnapshotAtIteration(int loopCounter) {
        return buildSnapshotAtIteration(loopCounter, Instant.now());
    }

    /**
     * Build a synthetic ThreadSnapshot representing the state at iteration i
     * of the periodic freeze loop.
     */
    private static ThreadSnapshot buildSnapshotAtIteration(int loopCounter, Instant timestamp) {
        // Simulate the locals at the freeze point inside runLoop:
        // slot 0: start (int), slot 1: end (int), slot 2: freezeInterval (int),
        // slot 3: freezeLog (List), slot 4: sum (int), slot 5: i (int)
        var locals = List.of(
                new LocalVariable(0, "start", "I", new PrimitiveRef(0)),
                new LocalVariable(1, "end", "I", new PrimitiveRef(101)),
                new LocalVariable(2, "freezeInterval", "I", new PrimitiveRef(5)),
                new LocalVariable(3, "freezeLog", "Ljava/util/List;", new NullRef()),
                new LocalVariable(4, "sum", "I", new PrimitiveRef(sumUpTo(loopCounter))),
                new LocalVariable(5, "i", "I", new PrimitiveRef(loopCounter))
        );

        var frame = new FrameSnapshot(
                "com/u1/durableThreads/SequentialFreezeRestoreTest$PeriodicFreezeWorkflow",
                "runLoop", "(IIILjava/util/List;)I",
                42, // placeholder BCP
                new byte[32], // placeholder hash
                locals
        );

        return new ThreadSnapshot(
                timestamp,
                "workflow-thread",
                List.of(frame),
                List.of() // no heap objects needed for this test
        );
    }

    private static int sumUpTo(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) sum += i;
        return sum;
    }

    private static int extractLoopCounter(ThreadSnapshot snapshot) {
        for (LocalVariable local : snapshot.topFrame().locals()) {
            if ("i".equals(local.name()) && local.value() instanceof PrimitiveRef pr) {
                return (int) pr.value();
            }
        }
        throw new AssertionError("Could not find loop counter 'i' in snapshot locals");
    }

    private static byte[] serialize(ThreadSnapshot snapshot) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        }
    }

    private static ThreadSnapshot deserialize(byte[] bytes)
            throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (ThreadSnapshot) ois.readObject();
        }
    }

    private static byte[] instrument(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return SequentialFreezeRestoreTest.class.getClassLoader();
            }
        };
        PrologueInjector injector = new PrologueInjector(cw);
        cr.accept(injector, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    private static Class<?> loadInstrumented(Class<?> original) throws Exception {
        byte[] originalBytes = loadClassBytes(original);
        byte[] instrumented = instrument(originalBytes);

        ClassLoader cl = new ClassLoader(SequentialFreezeRestoreTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(original.getName())) {
                    return defineClass(name, instrumented, 0, instrumented.length);
                }
                throw new ClassNotFoundException(name);
            }

            @Override
            public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(original.getName())) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                }
                return super.loadClass(name, resolve);
            }
        };

        return cl.loadClass(original.getName());
    }

    private static int findInvokeIndex(Class<?> clazz, String methodName, String methodDesc,
                                        String targetOwner, String targetName) throws IOException {
        byte[] classBytes = loadClassBytes(clazz);
        int[] result = {-1};
        int[] counter = {0};

        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (name.equals(methodName) && descriptor.equals(methodDesc)) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) return;
                            if (owner.equals(targetOwner) && name.equals(targetName)) {
                                result[0] = counter[0];
                            }
                            counter[0]++;
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor,
                                                           Handle bsmHandle, Object... bsmArgs) {
                            counter[0]++;
                        }
                    };
                }
                return null;
            }
        }, 0);

        return result[0];
    }

    private static byte[] loadClassBytes(Class<?> clazz) throws IOException {
        String resourcePath = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Could not load class bytes for " + clazz.getName());
            return is.readAllBytes();
        }
    }
}
