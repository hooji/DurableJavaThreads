package ai.jacc.durableThreads;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggressive stress tests for the PrologueInjector and replay mechanism.
 * Each scenario is instrumented, loaded via a custom ClassLoader, and executed
 * in both normal and replay modes.
 */
class AggressiveFreezeTest {

    // ===================================================================
    // SCENARIO CLASSES
    // ===================================================================

    /**
     * A chain of 15 methods, each calling the next.
     * Freeze happens at the very bottom (depth 15).
     */
    public static class DeepStackScenario {
        public static int depth01(int[] trace) { trace[0] = 1;  return depth02(trace); }
        public static int depth02(int[] trace) { trace[1] = 2;  return depth03(trace); }
        public static int depth03(int[] trace) { trace[2] = 3;  return depth04(trace); }
        public static int depth04(int[] trace) { trace[3] = 4;  return depth05(trace); }
        public static int depth05(int[] trace) { trace[4] = 5;  return depth06(trace); }
        public static int depth06(int[] trace) { trace[5] = 6;  return depth07(trace); }
        public static int depth07(int[] trace) { trace[6] = 7;  return depth08(trace); }
        public static int depth08(int[] trace) { trace[7] = 8;  return depth09(trace); }
        public static int depth09(int[] trace) { trace[8] = 9;  return depth10(trace); }
        public static int depth10(int[] trace) { trace[9] = 10; return depth11(trace); }
        public static int depth11(int[] trace) { trace[10] = 11; return depth12(trace); }
        public static int depth12(int[] trace) { trace[11] = 12; return depth13(trace); }
        public static int depth13(int[] trace) { trace[12] = 13; return depth14(trace); }
        public static int depth14(int[] trace) { trace[13] = 14; return depth15(trace); }
        public static int depth15(int[] trace) {
            trace[14] = 15;
            FreezePoint.hit();
            return 9999;
        }
    }

    /**
     * Recursive fibonacci that stores intermediate results in temp variables
     * to keep the operand stack clean.
     */
    public static class FibonacciScenario {
        public static int fib(int n, int[] freezeAtN) {
            if (n <= 1) {
                return n;
            }
            // Check if this is the recursion depth where we freeze
            if (n == freezeAtN[0]) {
                FreezePoint.hit();
            }
            // Store return values in temp vars — NOT on the operand stack
            int a = fib(n - 1, freezeAtN);
            int b = fib(n - 2, freezeAtN);
            int result = a + b;
            return result;
        }
    }

    /**
     * Freeze inside a try block. Verify exception handling survives instrumentation.
     */
    public static class TryCatchScenario {
        public static String freezeInTry(boolean shouldThrowAfter) {
            StringBuilder sb = new StringBuilder();
            try {
                sb.append("before-");
                FreezePoint.hit();
                sb.append("after-");
                if (shouldThrowAfter) {
                    throwHelper(); // separate method to keep stack clean
                }
                sb.append("end");
            } catch (RuntimeException e) {
                sb.append("caught-").append(e.getMessage());
            } finally {
                sb.append("-finally");
            }
            return sb.toString();
        }

        private static void throwHelper() {
            throw new RuntimeException("boom");
        }
    }

    /**
     * Freeze inside a switch statement (classic switch).
     */
    public static class SwitchScenario {
        public static String freezeInSwitch(int caseValue) {
            String result;
            switch (caseValue) {
                case 1:
                    result = "one";
                    break;
                case 2:
                    FreezePoint.hit();
                    result = "two-frozen";
                    break;
                case 3:
                    result = "three";
                    break;
                default:
                    result = "default";
                    break;
            }
            return result;
        }
    }

    /**
     * Nested loops with freeze. Outer loop index i=2, inner loop index j=3.
     */
    public static class NestedLoopScenario {
        public static int[] freezeInNestedLoop(int outerSize, int innerSize,
                                                int freezeI, int freezeJ) {
            int totalIterations = 0;
            int frozeAtTotal = -1;
            for (int i = 0; i < outerSize; i++) {
                for (int j = 0; j < innerSize; j++) {
                    if (i == freezeI && j == freezeJ) {
                        FreezePoint.hit();
                        frozeAtTotal = totalIterations;
                    }
                    totalIterations++;
                }
            }
            return new int[]{totalIterations, frozeAtTotal};
        }
    }

    /**
     * Method with many local variables of different types.
     * Verifies the __skip local doesn't collide with original locals.
     */
    public static class ManyLocalsScenario {
        public static long manyLocals(int seed) {
            int a = seed;
            int b = seed + 1;
            int c = seed + 2;
            long d = seed * 3L;
            double e = seed * 1.5;
            float f = seed * 0.5f;
            boolean g = seed > 0;
            String h = "local-" + seed;  // invoke (string concat)
            int i = a + b + c;
            long j = d + (long) e;
            FreezePoint.hit();
            // Use all locals after freeze point to ensure they survive
            long result = a + b + c + d + (long) e + (long) f + (g ? 1 : 0) + h.length() + i + j;
            return result;
        }
    }

    /**
     * While loop with break and continue.
     */
    public static class WhileBreakContinueScenario {
        public static int whileWithBreakContinue(int limit) {
            int count = 0;
            int sum = 0;
            while (count < limit) {
                count++;
                if (count % 3 == 0) {
                    continue; // skip multiples of 3
                }
                if (count == 7) {
                    FreezePoint.hit();
                }
                if (count > 10) {
                    break;
                }
                sum += count;
            }
            return sum;
        }
    }

    /**
     * Multiple freeze points in one method. Only one should fire
     * based on a condition.
     */
    public static class MultipleFreezePointsScenario {
        public static String multipleFreezePoints(int which) {
            String stage = "start";
            if (which == 1) {
                FreezePoint.hit();
                stage = "froze-at-1";
            }
            stage = stage + "-middle";
            if (which == 2) {
                FreezePoint.hit();
                stage = "froze-at-2";
            }
            stage = stage + "-end";
            return stage;
        }
    }

    /**
     * Chained method calls where each returns a value used by the next,
     * but always stored in temp vars (clean stack).
     */
    public static class ChainedComputationScenario {
        public static int chainedCompute(int input) {
            int v1 = step1(input);
            int v2 = step2(v1);
            int v3 = step3(v2);
            FreezePoint.hit();
            int v4 = step4(v3);
            int v5 = step5(v4);
            return v5;
        }

        static int step1(int x) { return x + 10; }
        static int step2(int x) { return x * 2; }
        static int step3(int x) { return x - 3; }
        static int step4(int x) { return x / 2; }
        static int step5(int x) { return x + 100; }
    }

    /**
     * Freeze marker class — shared with FreezeScenarioTest.
     */
    public static class FreezePoint {
        public static int hitCount = 0;
        public static void hit() { hitCount++; }
        public static void reset() { hitCount = 0; }
    }

    // ===================================================================
    // TESTS: NORMAL EXECUTION (correctness of instrumented bytecode)
    // ===================================================================

    @Test
    void deepStack15FramesNormal() throws Exception {
        Class<?> clazz = loadInstrumented(DeepStackScenario.class);
        FreezePoint.reset();

        int[] trace = new int[15];
        Method m = clazz.getMethod("depth01", int[].class);
        int result = (int) m.invoke(null, trace);

        assertEquals(9999, result);
        for (int i = 0; i < 15; i++) {
            assertEquals(i + 1, trace[i], "Depth " + (i + 1) + " should be traced");
        }
        assertEquals(1, FreezePoint.hitCount);
    }

    @Test
    void fibonacciNormal() throws Exception {
        Class<?> clazz = loadInstrumented(FibonacciScenario.class);
        FreezePoint.reset();

        // fib(10) = 55, freeze at n=5
        Method m = clazz.getMethod("fib", int.class, int[].class);
        int result = (int) m.invoke(null, 10, new int[]{5});

        assertEquals(55, result, "fib(10) should be 55");
        // fib(5) is called multiple times during fib(10) recursion
        assertTrue(FreezePoint.hitCount > 0, "hit() should be called at n=5");
    }

    @Test
    void tryCatchNormalNoException() throws Exception {
        Class<?> clazz = loadInstrumented(TryCatchScenario.class);
        FreezePoint.reset();

        Method m = clazz.getMethod("freezeInTry", boolean.class);
        String result = (String) m.invoke(null, false);

        assertEquals("before-after-end-finally", result);
        assertEquals(1, FreezePoint.hitCount);
    }

    @Test
    void tryCatchNormalWithException() throws Exception {
        Class<?> clazz = loadInstrumented(TryCatchScenario.class);
        FreezePoint.reset();

        Method m = clazz.getMethod("freezeInTry", boolean.class);
        String result = (String) m.invoke(null, true);

        assertEquals("before-after-caught-boom-finally", result);
        assertEquals(1, FreezePoint.hitCount);
    }

    @Test
    void switchCase2Normal() throws Exception {
        Class<?> clazz = loadInstrumented(SwitchScenario.class);
        FreezePoint.reset();

        Method m = clazz.getMethod("freezeInSwitch", int.class);
        assertEquals("two-frozen", m.invoke(null, 2));
        assertEquals(1, FreezePoint.hitCount);

        // Other cases should not hit freeze
        FreezePoint.reset();
        assertEquals("one", m.invoke(null, 1));
        assertEquals(0, FreezePoint.hitCount);

        assertEquals("three", m.invoke(null, 3));
        assertEquals("default", m.invoke(null, 99));
    }

    @Test
    void nestedLoopNormal() throws Exception {
        Class<?> clazz = loadInstrumented(NestedLoopScenario.class);
        FreezePoint.reset();

        Method m = clazz.getMethod("freezeInNestedLoop",
                int.class, int.class, int.class, int.class);
        int[] result = (int[]) m.invoke(null, 5, 5, 2, 3);

        assertEquals(25, result[0], "Total iterations: 5*5=25");
        assertEquals(13, result[1], "Freeze at i=2,j=3 → iteration 2*5+3=13");
        assertEquals(1, FreezePoint.hitCount);
    }

    @Test
    void manyLocalsNormal() throws Exception {
        Class<?> clazz = loadInstrumented(ManyLocalsScenario.class);
        FreezePoint.reset();

        Method m = clazz.getMethod("manyLocals", int.class);
        long result = (long) m.invoke(null, 10);

        // Compute expected:
        int seed = 10;
        int a = seed, b = seed + 1, c = seed + 2;
        long d = seed * 3L;
        double e = seed * 1.5;
        float f = seed * 0.5f;
        boolean g = seed > 0;
        String h = "local-" + seed;
        int i = a + b + c;
        long j = d + (long) e;
        long expected = a + b + c + d + (long) e + (long) f + (g ? 1 : 0) + h.length() + i + j;

        assertEquals(expected, result);
        assertEquals(1, FreezePoint.hitCount);
    }

    @Test
    void whileBreakContinueNormal() throws Exception {
        Class<?> clazz = loadInstrumented(WhileBreakContinueScenario.class);
        FreezePoint.reset();

        Method m = clazz.getMethod("whileWithBreakContinue", int.class);
        int result = (int) m.invoke(null, 15);

        // count goes 1..15 but breaks at count > 10
        // skips multiples of 3: 3, 6, 9
        // sums: 1+2+4+5+7+8+10 = 37
        assertEquals(37, result);
        assertEquals(1, FreezePoint.hitCount); // hit at count==7
    }

    @Test
    void multipleFreezePointsNormal() throws Exception {
        Class<?> clazz = loadInstrumented(MultipleFreezePointsScenario.class);

        Method m = clazz.getMethod("multipleFreezePoints", int.class);

        FreezePoint.reset();
        assertEquals("froze-at-1-middle-end", m.invoke(null, 1));
        assertEquals(1, FreezePoint.hitCount);

        FreezePoint.reset();
        // stage = "start", then += "-middle" → "start-middle",
        // then overwritten by stage = "froze-at-2", then += "-end"
        assertEquals("froze-at-2-end", m.invoke(null, 2));
        assertEquals(1, FreezePoint.hitCount);

        FreezePoint.reset();
        assertEquals("start-middle-end", m.invoke(null, 0));
        assertEquals(0, FreezePoint.hitCount);
    }

    @Test
    void chainedComputationNormal() throws Exception {
        Class<?> clazz = loadInstrumented(ChainedComputationScenario.class);
        FreezePoint.reset();

        Method m = clazz.getMethod("chainedCompute", int.class);
        int result = (int) m.invoke(null, 5);

        // step1: 5+10=15, step2: 15*2=30, step3: 30-3=27
        // freeze
        // step4: 27/2=13, step5: 13+100=113
        assertEquals(113, result);
        assertEquals(1, FreezePoint.hitCount);
    }

    // ===================================================================
    // TESTS: REPLAY MODE (skip-check mechanism)
    // ===================================================================

    @Test
    void deepStackReplaySkipsAtBottom() throws Exception {
        Class<?> clazz = loadInstrumented(DeepStackScenario.class);

        // depth15 has FreezePoint.hit() — find its invoke index
        int hitIndex = findInvokeIndex(DeepStackScenario.class,
                "depth15", "([I)I",
                "ai/jacc/durableThreads/AggressiveFreezeTest$FreezePoint", "hit");
        assertTrue(hitIndex >= 0);

        FreezePoint.reset();
        int[] trace = new int[15];
        ReplayState.activate(new int[]{hitIndex});
        try {
            Method m = clazz.getMethod("depth15", int[].class);
            int result = (int) m.invoke(null, trace);

            assertEquals(9999, result, "Return value should be preserved");
            assertEquals(15, trace[14], "Local assignment before freeze should execute");
            assertEquals(0, FreezePoint.hitCount, "hit() should be skipped");
        } finally {
            ReplayState.deactivate();
        }
    }

    @Test
    void fibonacciReplaySkipsHit() throws Exception {
        Class<?> clazz = loadInstrumented(FibonacciScenario.class);

        int hitIndex = findInvokeIndex(FibonacciScenario.class,
                "fib", "(I[I)I",
                "ai/jacc/durableThreads/AggressiveFreezeTest$FreezePoint", "hit");
        assertTrue(hitIndex >= 0);

        FreezePoint.reset();
        ReplayState.activate(new int[]{hitIndex});
        try {
            Method m = clazz.getMethod("fib", int.class, int[].class);
            int result = (int) m.invoke(null, 10, new int[]{5});

            assertEquals(55, result, "fib(10) should still be 55 in replay mode");
            assertEquals(0, FreezePoint.hitCount, "hit() should be skipped");
        } finally {
            ReplayState.deactivate();
        }
    }

    @Test
    void tryCatchReplaySkipsHit() throws Exception {
        Class<?> clazz = loadInstrumented(TryCatchScenario.class);

        int hitIndex = findInvokeIndex(TryCatchScenario.class,
                "freezeInTry", "(Z)Ljava/lang/String;",
                "ai/jacc/durableThreads/AggressiveFreezeTest$FreezePoint", "hit");
        assertTrue(hitIndex >= 0);

        FreezePoint.reset();
        ReplayState.activate(new int[]{hitIndex});
        try {
            Method m = clazz.getMethod("freezeInTry", boolean.class);
            String result = (String) m.invoke(null, false);

            // The skip mechanism skips ALL invokes up to the target (__skip >= index).
            // sb.append("before-") is invoke 0, hit() is invoke 1 (target).
            // Both are skipped. In a real restore, JDI sets locals; here we see
            // the effect of skipping pre-freeze invokes: "before-" is missing.
            assertEquals("after-end-finally", result);
            assertEquals(0, FreezePoint.hitCount);
        } finally {
            ReplayState.deactivate();
        }
    }

    @Test
    void switchReplaySkipsHit() throws Exception {
        Class<?> clazz = loadInstrumented(SwitchScenario.class);

        int hitIndex = findInvokeIndex(SwitchScenario.class,
                "freezeInSwitch", "(I)Ljava/lang/String;",
                "ai/jacc/durableThreads/AggressiveFreezeTest$FreezePoint", "hit");
        assertTrue(hitIndex >= 0);

        FreezePoint.reset();
        ReplayState.activate(new int[]{hitIndex});
        try {
            Method m = clazz.getMethod("freezeInSwitch", int.class);
            String result = (String) m.invoke(null, 2);

            assertEquals("two-frozen", result);
            assertEquals(0, FreezePoint.hitCount);
        } finally {
            ReplayState.deactivate();
        }
    }

    @Test
    void nestedLoopReplaySkipsHit() throws Exception {
        Class<?> clazz = loadInstrumented(NestedLoopScenario.class);

        int hitIndex = findInvokeIndex(NestedLoopScenario.class,
                "freezeInNestedLoop", "(IIII)[I",
                "ai/jacc/durableThreads/AggressiveFreezeTest$FreezePoint", "hit");
        assertTrue(hitIndex >= 0);

        FreezePoint.reset();
        ReplayState.activate(new int[]{hitIndex});
        try {
            Method m = clazz.getMethod("freezeInNestedLoop",
                    int.class, int.class, int.class, int.class);
            int[] result = (int[]) m.invoke(null, 5, 5, 2, 3);

            assertEquals(25, result[0], "All iterations should still execute");
            assertEquals(13, result[1], "frozeAtTotal should still be recorded");
            assertEquals(0, FreezePoint.hitCount, "hit() should be skipped");
        } finally {
            ReplayState.deactivate();
        }
    }

    @Test
    void manyLocalsReplaySkipsHit() throws Exception {
        Class<?> clazz = loadInstrumented(ManyLocalsScenario.class);

        int hitIndex = findInvokeIndex(ManyLocalsScenario.class,
                "manyLocals", "(I)J",
                "ai/jacc/durableThreads/AggressiveFreezeTest$FreezePoint", "hit");
        assertTrue(hitIndex >= 0);

        FreezePoint.reset();
        ReplayState.activate(new int[]{hitIndex});
        try {
            Method m = clazz.getMethod("manyLocals", int.class);

            // The skip mechanism skips ALL invokes up to the target (__skip >= index).
            // The string concat "local-" + seed is an invokedynamic before the freeze
            // point, so it's skipped and h gets null (default for Object). When the
            // post-freeze code calls h.length(), it throws NullPointerException.
            // In a real restore, JDI would set h to the correct value.
            var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> m.invoke(null, 10));
            assertInstanceOf(NullPointerException.class, thrown.getCause(),
                    "h is null because string concat invoke was skipped");
            assertEquals(0, FreezePoint.hitCount);
        } finally {
            ReplayState.deactivate();
        }
    }

    @Test
    void chainedComputationReplaySkipsHit() throws Exception {
        Class<?> clazz = loadInstrumented(ChainedComputationScenario.class);

        int hitIndex = findInvokeIndex(ChainedComputationScenario.class,
                "chainedCompute", "(I)I",
                "ai/jacc/durableThreads/AggressiveFreezeTest$FreezePoint", "hit");
        assertTrue(hitIndex >= 0);

        FreezePoint.reset();
        ReplayState.activate(new int[]{hitIndex});
        try {
            Method m = clazz.getMethod("chainedCompute", int.class);
            int result = (int) m.invoke(null, 5);

            // The skip mechanism skips ALL invokes up to the target (__skip >= index).
            // step1/step2/step3 (invokes 0-2) and hit() (invoke 3) are all skipped.
            // v1=v2=v3=0 (default int). step4(0)=0, step5(0)=100.
            // In a real restore, JDI would set v1=15, v2=30, v3=27 before resuming.
            assertEquals(100, result, "Pre-freeze invokes skipped, post-freeze use defaults");
            assertEquals(0, FreezePoint.hitCount);
        } finally {
            ReplayState.deactivate();
        }
    }

    // ===================================================================
    // BYTECODE VERIFICATION
    // ===================================================================

    @Test
    void allAggressiveScenariosPassVerification() throws Exception {
        Class<?>[] scenarios = {
                DeepStackScenario.class, FibonacciScenario.class,
                TryCatchScenario.class, SwitchScenario.class,
                NestedLoopScenario.class, ManyLocalsScenario.class,
                WhileBreakContinueScenario.class, MultipleFreezePointsScenario.class,
                ChainedComputationScenario.class
        };

        for (Class<?> scenario : scenarios) {
            byte[] original = loadClassBytes(scenario);
            byte[] instrumented = instrument(original);

            ClassReader cr = new ClassReader(instrumented);
            assertDoesNotThrow(() ->
                            cr.accept(new org.objectweb.asm.util.CheckClassAdapter(
                                    new ClassVisitor(Opcodes.ASM9) {}), 0),
                    "Bytecode verification failed for " + scenario.getSimpleName());
        }
    }

    @Test
    void instrumentedBytecodeIsReasonablyLarger() throws Exception {
        // The prologue should add modest overhead, not explode the bytecode size
        byte[] original = loadClassBytes(DeepStackScenario.class);
        byte[] instrumented = instrument(original);

        double ratio = (double) instrumented.length / original.length;
        assertTrue(ratio < 5.0,
                "Instrumented bytecode should not be more than 5x larger. " +
                "Ratio: " + String.format("%.2f", ratio) +
                " (" + original.length + " → " + instrumented.length + ")");
    }

    // ===================================================================
    // HELPERS
    // ===================================================================

    private static byte[] instrument(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return AggressiveFreezeTest.class.getClassLoader();
            }
        };
        PrologueInjector injector = new PrologueInjector(cw);
        cr.accept(injector, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    private static Class<?> loadInstrumented(Class<?> original) throws Exception {
        byte[] originalBytes = loadClassBytes(original);
        byte[] instrumented = instrument(originalBytes);

        ClassLoader cl = new ClassLoader(AggressiveFreezeTest.class.getClassLoader()) {
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
                            // Skip invokespecial <init> — matches PrologueInjector behavior
                            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
                                return;
                            }
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
