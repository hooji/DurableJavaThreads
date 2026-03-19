package com.u1.durableThreads.internal;

import com.u1.durableThreads.PrologueInjector;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link OperandStackChecker}.
 *
 * <p>These tests verify that the checker gracefully handles failure modes
 * that occur in practice with instrumented bytecode. A past regression
 * caused "can't verify" scenarios (BCI not found after prologue injection,
 * analyzer exception on complex bytecode) to be treated as confirmed
 * non-empty-stack violations, blocking every freeze attempt.</p>
 *
 * <p>Key invariant: {@code checkStackAtInvoke} must return {@code null}
 * (meaning "no violation detected") when analysis <em>cannot be performed</em>,
 * and only return a non-null error message when a non-empty stack is
 * <em>positively confirmed</em>.</p>
 */
class OperandStackCheckerRegressionTest {

    // ---------------------------------------------------------------
    // BCI mismatch after prologue injection
    // ---------------------------------------------------------------

    /**
     * After PrologueInjector adds the replay prologue, the bytecode offsets
     * change. A BCI that was valid in the original bytecode may not match any
     * instruction in the instrumented bytecode. The checker must return null
     * (not an error message) when the target BCI cannot be found.
     */
    @Test
    void returnsNullWhenBciNotFoundInInstrumentedBytecode() throws Exception {
        byte[] original = loadClassBytes(SampleForInstrumentation.class);
        byte[] instrumented = instrument(original);

        // Pick a BCI that corresponds to the original code but is likely
        // shifted or absent in the instrumented version. BCI 999 is almost
        // certainly not a valid instruction offset.
        String result = OperandStackChecker.checkStackAtInvoke(
                instrumented, "doWork", "(I)I", 999);

        assertNull(result,
                "When BCI is not found in instrumented bytecode, checker must return null "
                + "(can't verify ≠ confirmed violation)");
    }

    /**
     * Same as above but with a BCI of 0 on instrumented bytecode — the
     * prologue is now at BCI 0, not the original user code. The checker
     * should still not produce a false positive.
     */
    @Test
    void returnsNullOrCleanForBciZeroOnInstrumentedCode() throws Exception {
        byte[] original = loadClassBytes(SampleForInstrumentation.class);
        byte[] instrumented = instrument(original);

        // BCI 0 in the instrumented code is the start of the prologue.
        // It should either: return null (BCI doesn't land on the expected
        // invoke), or return null (stack is clean at prologue start).
        // It must NOT return an error string.
        String result = OperandStackChecker.checkStackAtInvoke(
                instrumented, "doWork", "(I)I", 0);

        assertNull(result,
                "BCI 0 on instrumented code (prologue start) must not produce a false positive");
    }

    /**
     * Use the ORIGINAL (pre-instrumentation) bytecode with BCI 0.
     * In doWork(), BCI 0 is ILOAD_0 (loading the argument), not an invoke.
     * The checker should return null since the instruction at BCI 0 is not
     * a method call.
     */
    @Test
    void returnsNullForNonInvokeInstruction() throws Exception {
        byte[] original = loadClassBytes(SampleForInstrumentation.class);

        // BCI 0 in the original doWork() is the load instruction, not an invoke
        String result = OperandStackChecker.checkStackAtInvoke(
                original, "doWork", "(I)I", 0);

        assertNull(result,
                "Non-invoke instruction should return null (not a method call to check)");
    }

    // ---------------------------------------------------------------
    // Garbage / corrupt bytecode
    // ---------------------------------------------------------------

    /**
     * Completely invalid bytecode must return null (can't parse → can't verify),
     * never throw and never return an error string.
     */
    @Test
    void returnsNullForGarbageBytecode() {
        byte[] garbage = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};

        String result = OperandStackChecker.checkStackAtInvoke(
                garbage, "anyMethod", "()V", 0);

        assertNull(result,
                "Garbage bytecode must return null (analysis failed → can't verify)");
    }

    /**
     * Truncated but valid-looking class header must return null, not an error.
     */
    @Test
    void returnsNullForTruncatedClassFile() {
        // Valid magic number but truncated
        byte[] truncated = new byte[]{
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x37  // version 55 (Java 11) header, then nothing
        };

        String result = OperandStackChecker.checkStackAtInvoke(
                truncated, "anyMethod", "()V", 0);

        assertNull(result, "Truncated class file must return null");
    }

    /**
     * Empty byte array must return null.
     */
    @Test
    void returnsNullForEmptyBytecode() {
        String result = OperandStackChecker.checkStackAtInvoke(
                new byte[0], "m", "()V", 0);

        assertNull(result, "Empty bytecode must return null");
    }

    // ---------------------------------------------------------------
    // Instrumented bytecode with known-clean stacks
    // ---------------------------------------------------------------

    /**
     * Instrument a class, then verify that checking at the original invoke
     * BCIs (from the uninstrumented code) returns null rather than false
     * positives. This is the exact scenario that broke in production: freeze
     * captures the BCI from the running (instrumented) code, then the checker
     * tries to find it in the instrumented bytecode. After prologue injection,
     * the BCI often doesn't match.
     */
    @Test
    void instrumentedBytecodeNeverProducesFalsePositives() throws Exception {
        byte[] original = loadClassBytes(SampleForInstrumentation.class);
        byte[] instrumented = instrument(original);

        // Try many BCIs — none should produce a false positive on the
        // instrumented bytecode. We sweep a range of common BCI values.
        for (int bci = 0; bci < 200; bci++) {
            String result = OperandStackChecker.checkStackAtInvoke(
                    instrumented, "doWork", "(I)I", bci);

            // Result should be null for most BCIs (not found → can't verify).
            // If a BCI happens to land on a prologue invoke with a clean stack,
            // it should also be null (clean).
            // It should NEVER return an error string for a method that only
            // calls methods with a clean stack.
            assertNull(result,
                    "BCI " + bci + " on instrumented doWork() must not produce a false positive. "
                    + "Got: " + result);
        }
    }

    /**
     * Verify the checker still correctly detects a REAL dirty stack in
     * uninstrumented bytecode (sanity check that our fixes didn't break
     * true positive detection).
     */
    @Test
    void stillDetectsDirtyStackInUninstrumentedCode() {
        byte[] classBytes = generateClass("DirtyStackCheck", (cw, className) -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "foo", "()I", null, null);
            mv.visitCode();
            // compute() → int on stack
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, className, "compute", "()I", false);
            // bar() called with compute()'s return value still on stack
            // BCI: INVOKESTATIC compute (3 bytes at BCI 0), INVOKESTATIC bar at BCI 3
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, className, "bar", "()I", false);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();

            for (String name : new String[]{"compute", "bar"}) {
                MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        name, "()I", null, null);
                m.visitCode();
                m.visitInsn(Opcodes.ICONST_1);
                m.visitInsn(Opcodes.IRETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        });

        // At BCI 3 (INVOKESTATIC bar), compute()'s return value is on the stack
        String error = OperandStackChecker.checkStackAtInvoke(classBytes, "foo", "()I", 3);
        assertNotNull(error, "Should still detect real dirty stack");
        assertTrue(error.contains("extra slot"), "Error should mention extra slots");
    }

    // ---------------------------------------------------------------
    // Negative BCI and edge cases
    // ---------------------------------------------------------------

    @Test
    void returnsNullForNegativeBci() throws Exception {
        byte[] classBytes = loadClassBytes(SampleForInstrumentation.class);
        String result = OperandStackChecker.checkStackAtInvoke(
                classBytes, "doWork", "(I)I", -1);
        assertNull(result, "Negative BCI must return null (can't find → can't verify)");
    }

    @Test
    void returnsNullForHugeBci() throws Exception {
        byte[] classBytes = loadClassBytes(SampleForInstrumentation.class);
        String result = OperandStackChecker.checkStackAtInvoke(
                classBytes, "doWork", "(I)I", Integer.MAX_VALUE);
        assertNull(result, "Huge BCI must return null");
    }

    // ---------------------------------------------------------------
    // Test target class
    // ---------------------------------------------------------------

    /**
     * Sample class used for instrumentation testing. All methods have clean
     * operand stacks at every invoke site.
     */
    static class SampleForInstrumentation {
        public static int doWork(int x) {
            int a = helper(x);
            int b = helper(a + 1);
            return a + b;
        }

        public static int helper(int v) {
            return v * 2;
        }

        public void instanceMethod(String s) {
            int len = s.length();
            System.out.println(len);
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static byte[] instrument(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return OperandStackCheckerRegressionTest.class.getClassLoader();
            }
        };
        PrologueInjector injector = new PrologueInjector(cw);
        cr.accept(injector, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    private static byte[] loadClassBytes(Class<?> clazz) throws IOException {
        String resourcePath = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is);
            return is.readAllBytes();
        }
    }

    @FunctionalInterface
    interface ClassBodyGenerator {
        void generate(ClassWriter cw, String className);
    }

    private static byte[] generateClass(String simpleName, ClassBodyGenerator body) {
        String fullName = "com/test/" + simpleName;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fullName, null,
                "java/lang/Object", null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        body.generate(cw, fullName);

        cw.visitEnd();
        return cw.toByteArray();
    }
}
