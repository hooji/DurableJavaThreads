package ai.jacc.durableThreads.internal;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class OperandStackCheckerTest {

    /**
     * Generate a class where a method call happens with a clean stack.
     * e.g.: void foo() { bar(); }
     */
    @Test
    void cleanStackAtInvoke() {
        byte[] classBytes = generateClass("CleanStack", (cw, className) -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "foo", "()V", null, null);
            mv.visitCode();
            // bar() — stack is empty before the invoke
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, className, "bar", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();

            // bar() — trivial
            MethodVisitor bar = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "bar", "()V", null, null);
            bar.visitCode();
            bar.visitInsn(Opcodes.RETURN);
            bar.visitMaxs(0, 0);
            bar.visitEnd();
        });

        // BCI of the INVOKESTATIC bar()V in foo():
        // foo() bytecode: INVOKESTATIC (3 bytes) at BCI 0, RETURN at BCI 3
        String error = OperandStackChecker.checkStackAtInvoke(classBytes, "foo", "()V", 0);
        assertNull(error, "Stack should be clean at call to bar()");
    }

    /**
     * Generate a class where a method call happens with extra values on the stack.
     * e.g.: int foo() { return compute() + bar(); }
     * At the call to bar(), compute()'s return value is on the stack.
     */
    @Test
    void dirtyStackAtInvoke() {
        byte[] classBytes = generateClass("DirtyStack", (cw, className) -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "foo", "()I", null, null);
            mv.visitCode();
            // compute() → int on stack
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, className, "compute", "()I", false);
            // bar() → int on stack (now stack has 2 ints)
            // BCI: INVOKESTATIC compute (3 bytes at BCI 0), INVOKESTATIC bar at BCI 3
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, className, "bar", "()I", false);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();

            // compute() and bar() — trivial
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

        // At BCI 3 (the INVOKESTATIC bar), compute()'s return value is on the stack
        String error = OperandStackChecker.checkStackAtInvoke(classBytes, "foo", "()I", 3);
        assertNotNull(error, "Should detect non-empty stack at call to bar()");
        assertTrue(error.contains("extra slot"), "Error should mention extra slots");
    }

    /**
     * Verify a clean stack at a virtual method call (receiver + args consumed).
     * e.g.: void foo(String s) { s.length(); }
     */
    @Test
    void cleanStackAtVirtualInvoke() {
        byte[] classBytes = generateClass("VirtualClean", (cw, className) -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "foo", "(Ljava/lang/String;)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            // ALOAD 0 at BCI 0 (1 byte), INVOKEVIRTUAL at BCI 1
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                    "length", "()I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });

        // At BCI 1: stack has [String] which is the receiver — exactly what the invoke expects
        String error = OperandStackChecker.checkStackAtInvoke(classBytes, "foo",
                "(Ljava/lang/String;)V", 1);
        assertNull(error, "Stack should be clean (only receiver, consumed by invoke)");
    }

    @Test
    void returnsNullForUnknownMethod() {
        byte[] classBytes = generateClass("Empty", (cw, className) -> {});
        String error = OperandStackChecker.checkStackAtInvoke(classBytes,
                "nonexistent", "()V", 0);
        // Should return method-not-found message but not crash
        assertNotNull(error);
    }

    // --- Helper ---

    @FunctionalInterface
    interface ClassBodyGenerator {
        void generate(ClassWriter cw, String className);
    }

    private static byte[] generateClass(String simpleName, ClassBodyGenerator body) {
        String fullName = "com/test/" + simpleName;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fullName, null,
                "java/lang/Object", null);

        // Default constructor
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
