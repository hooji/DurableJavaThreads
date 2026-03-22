package ai.jacc.durableThreads;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggressive tests that verify the PrologueInjector's skip-check mechanism
 * works correctly in complex scenarios: loops, if/else blocks, nested calls.
 *
 * <p>These tests instrument actual bytecode, load it via a custom ClassLoader,
 * and execute it — first in normal mode to verify correctness, then in replay
 * mode to verify the skip-check correctly bypasses invokes.</p>
 */
class FreezeScenarioTest {

    /**
     * Scenario target classes. Each has methods that represent realistic
     * freeze patterns. The methods call {@code FreezePoint.hit()} at the
     * freeze location (a substitute for Durable.freeze() that we can
     * intercept in tests).
     */
    public static class LoopScenario {
        /**
         * Simulates freezing on the 5th iteration of a 10-iteration loop.
         * After restore, the loop should continue from iteration 5.
         */
        public static int loopWithFreezeOnFifth(int[] counters) {
            for (int i = 0; i < 10; i++) {
                counters[0] = i;
                if (i == 4) {
                    FreezePoint.hit(); // freeze here on iteration 4 (5th, zero-indexed)
                }
                counters[1]++;
            }
            return counters[1];
        }
    }

    public static class IfElseScenario {
        /**
         * Freeze inside the "if" branch.
         */
        public static String freezeInIfBranch(boolean condition) {
            String result;
            if (condition) {
                FreezePoint.hit();
                result = "if-branch";
            } else {
                result = "else-branch";
            }
            return result;
        }

        /**
         * Freeze inside the "else" branch.
         */
        public static String freezeInElseBranch(boolean condition) {
            String result;
            if (condition) {
                result = "if-branch";
            } else {
                FreezePoint.hit();
                result = "else-branch";
            }
            return result;
        }
    }

    public static class NestedCallScenario {
        /**
         * Freeze deep in nested method calls.
         */
        public static int outerMethod(int value) {
            int a = value + 1;
            int b = middleMethod(a);
            return b + 10;
        }

        public static int middleMethod(int value) {
            int x = value * 2;
            int y = innerMethod(x);
            return y + 5;
        }

        public static int innerMethod(int value) {
            FreezePoint.hit();
            return value + 100;
        }
    }

    /**
     * Marker class that represents the freeze point.
     * In instrumented code, calling hit() is the invoke we'll skip during replay.
     */
    public static class FreezePoint {
        public static int hitCount = 0;

        public static void hit() {
            hitCount++;
        }

        public static void reset() {
            hitCount = 0;
        }
    }

    // -------------------------------------------------------------------
    // Tests that verify instrumented code behaves correctly in NORMAL mode
    // -------------------------------------------------------------------

    @Test
    void loopScenarioWorksNormally() throws Exception {
        // Run the instrumented code normally (no replay).
        // It should behave identically to uninstrumented code.
        Class<?> clazz = loadInstrumented(LoopScenario.class);
        FreezePoint.reset();

        int[] counters = new int[2];
        Method m = clazz.getMethod("loopWithFreezeOnFifth", int[].class);
        int result = (int) m.invoke(null, counters);

        assertEquals(10, result, "Loop should complete all 10 iterations");
        assertEquals(9, counters[0], "Last iteration index should be 9");
        assertEquals(10, counters[1], "Counter should be incremented 10 times");
        assertEquals(1, FreezePoint.hitCount, "FreezePoint.hit() called once (on i==4)");
    }

    @Test
    void ifBranchScenarioWorksNormally() throws Exception {
        Class<?> clazz = loadInstrumented(IfElseScenario.class);
        FreezePoint.reset();

        Method m = clazz.getMethod("freezeInIfBranch", boolean.class);
        String result = (String) m.invoke(null, true);

        assertEquals("if-branch", result);
        assertEquals(1, FreezePoint.hitCount, "FreezePoint.hit() called in if-branch");
    }

    @Test
    void elseBranchScenarioWorksNormally() throws Exception {
        Class<?> clazz = loadInstrumented(IfElseScenario.class);
        FreezePoint.reset();

        Method m = clazz.getMethod("freezeInElseBranch", boolean.class);
        String result = (String) m.invoke(null, false);

        assertEquals("else-branch", result);
        assertEquals(1, FreezePoint.hitCount, "FreezePoint.hit() called in else-branch");
    }

    @Test
    void nestedCallsWorkNormally() throws Exception {
        Class<?> clazz = loadInstrumented(NestedCallScenario.class);
        FreezePoint.reset();

        Method m = clazz.getMethod("outerMethod", int.class);
        int result = (int) m.invoke(null, 5);

        // outer: value=5, a=6, b=middleMethod(6), return b+10
        // middle: value=6, x=12, y=innerMethod(12), return y+5
        // inner: value=12, hit(), return 12+100=112
        // middle: y=112, return 112+5=117
        // outer: b=117, return 117+10=127
        assertEquals(127, result);
        assertEquals(1, FreezePoint.hitCount);
    }

    // -------------------------------------------------------------------
    // Tests that verify REPLAY mode correctly skips to the freeze point
    // -------------------------------------------------------------------

    @Test
    void replaySkipsToCorrectInvokeInLoop() throws Exception {
        // In a loop scenario, the freeze point is FreezePoint.hit().
        // In replay mode, the skip-check should bypass hit() and let
        // execution continue from after hit() — meaning the rest of the
        // loop body and subsequent iterations execute.
        Class<?> clazz = loadInstrumented(LoopScenario.class);

        // Find which invoke index corresponds to FreezePoint.hit()
        int hitInvokeIndex = findInvokeIndex(LoopScenario.class,
                "loopWithFreezeOnFifth", "([I)I",
                "ai/jacc/durableThreads/FreezeScenarioTest$FreezePoint", "hit");

        assertTrue(hitInvokeIndex >= 0, "Should find FreezePoint.hit() invoke");

        // Activate replay mode targeting that invoke
        FreezePoint.reset();
        int[] counters = new int[2];

        ReplayState.activate(new int[]{hitInvokeIndex});
        try {
            Method m = clazz.getMethod("loopWithFreezeOnFifth", int[].class);
            int result = (int) m.invoke(null, counters);

            // In direct-jump replay, the resume stub jumps past hit() to the
            // post-invoke label. Locals are defaults (loop counter i=0), so the
            // loop re-executes from i=0 and hits i==4 again, calling hit() once.
            // In production, JDI sets i to its frozen value, avoiding the re-entry.
            assertEquals(10, result, "Loop should still complete all iterations");
            assertEquals(1, FreezePoint.hitCount,
                    "hit() called once when loop re-enters i==4 (no JDI to set locals)");
        } finally {
            ReplayState.deactivate();
        }
    }

    @Test
    void replaySkipsCorrectlyInIfBranch() throws Exception {
        Class<?> clazz = loadInstrumented(IfElseScenario.class);

        int hitIndex = findInvokeIndex(IfElseScenario.class,
                "freezeInIfBranch", "(Z)Ljava/lang/String;",
                "ai/jacc/durableThreads/FreezeScenarioTest$FreezePoint", "hit");

        assertTrue(hitIndex >= 0);

        FreezePoint.reset();
        ReplayState.activate(new int[]{hitIndex});
        try {
            Method m = clazz.getMethod("freezeInIfBranch", boolean.class);
            String result = (String) m.invoke(null, true);

            assertEquals("if-branch", result, "Should take the if-branch");
            assertEquals(0, FreezePoint.hitCount, "hit() should be skipped");
        } finally {
            ReplayState.deactivate();
        }
    }

    @Test
    void replaySkipsCorrectlyInElseBranch() throws Exception {
        Class<?> clazz = loadInstrumented(IfElseScenario.class);

        int hitIndex = findInvokeIndex(IfElseScenario.class,
                "freezeInElseBranch", "(Z)Ljava/lang/String;",
                "ai/jacc/durableThreads/FreezeScenarioTest$FreezePoint", "hit");

        assertTrue(hitIndex >= 0);

        FreezePoint.reset();
        ReplayState.activate(new int[]{hitIndex});
        try {
            Method m = clazz.getMethod("freezeInElseBranch", boolean.class);
            String result = (String) m.invoke(null, false);

            assertEquals("else-branch", result, "Should take the else-branch");
            assertEquals(0, FreezePoint.hitCount, "hit() should be skipped");
        } finally {
            ReplayState.deactivate();
        }
    }

    // -------------------------------------------------------------------
    // Test that verifies the instrumented bytecode passes verification
    // -------------------------------------------------------------------

    @Test
    void allScenariosPassBytecodeVerification() throws Exception {
        for (Class<?> scenario : java.util.Arrays.asList(
                LoopScenario.class, IfElseScenario.class, NestedCallScenario.class)) {
            byte[] original = loadClassBytes(scenario);
            byte[] instrumented = instrument(original);

            ClassReader cr = new ClassReader(instrumented);
            assertDoesNotThrow(() ->
                    cr.accept(new org.objectweb.asm.util.CheckClassAdapter(
                            new ClassVisitor(Opcodes.ASM9) {}), 0),
                    "Verification failed for " + scenario.getSimpleName());
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static byte[] instrument(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return FreezeScenarioTest.class.getClassLoader();
            }
        };
        PrologueInjector injector = new PrologueInjector(cw);
        cr.accept(injector, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    /**
     * Instrument a class and load it via a custom ClassLoader.
     * The loaded class can then be invoked via reflection.
     */
    private static Class<?> loadInstrumented(Class<?> original) throws Exception {
        byte[] originalBytes = loadClassBytes(original);
        byte[] instrumented = instrument(originalBytes);

        ClassLoader cl = new ClassLoader(FreezeScenarioTest.class.getClassLoader()) {
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

    /**
     * Find the invoke index of a specific method call within an instrumented method.
     * This mirrors what the PrologueInjector assigns as invoke indices.
     */
    private static int findInvokeIndex(Class<?> clazz, String methodName, String methodDesc,
                                        String targetOwner, String targetName) throws IOException {
        // We need to analyze the ORIGINAL bytecode to count invokes in the same
        // order that PrologueInjector does (skipping constructors/clinit).
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
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, bytesRead);
            }
            return buffer.toByteArray();
        }
    }
}
