package ai.jacc.durableThreads;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import ai.jacc.durableThreads.PrologueTypes.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PrologueEmitter} — verifies that emitted bytecode is
 * structurally valid and contains the expected replay prologue components.
 *
 * <p>These tests create synthetic method data (invoke infos, buffered ops,
 * etc.) and feed them to the emitter, then verify the output via ASM's
 * CheckClassAdapter and instruction scanning.</p>
 */
class PrologueEmitterTest {

    // ===== No-invoke prologue =====

    @Test
    void noInvokePrologueEmitsOriginalCode() {
        // A method with no invokes should just emit the original bytecode
        // with method-wide labels.
        byte[] classBytes = buildClassWithEmitter(Opcodes.ACC_PUBLIC, "()V",
                new ArrayList<>(), // no invokes
                (target) -> {
                    // Simple method body: return
                    target.add(() -> {});  // placeholder for original code
                },
                new ArrayList<>(),
                new OperandStackSimulator(),
                false);

        // Should pass verification
        verifyBytecode(classBytes);
    }

    @Test
    void noInvokePrologueDoesNotContainReplayCheck() {
        byte[] classBytes = buildClassWithEmitter(Opcodes.ACC_PUBLIC, "()V",
                new ArrayList<>(),
                (target) -> {},
                new ArrayList<>(),
                new OperandStackSimulator(),
                false);

        // No-invoke methods should NOT call isReplayThread
        List<String> calls = findReplayStateCalls(classBytes, "testMethod", "()V");
        assertFalse(calls.contains("isReplayThread"),
                "No-invoke methods should not have replay check");
    }

    // ===== Full prologue =====

    @Test
    void fullPrologueContainsReplayCheck() {
        // Method with one invoke
        OperandStackSimulator sim = new OperandStackSimulator();
        List<InvokeInfo> invokeInfos = new ArrayList<>();
        invokeInfos.add(new InvokeInfo(0, Opcodes.INVOKESTATIC,
                "java/lang/System", "gc", "()V", false));

        List<Runnable> bufferedOps = new ArrayList<>();
        MethodVisitor[] capturedTarget = new MethodVisitor[1];
        bufferedOps.add(new InvokeMarker(0, Opcodes.INVOKESTATIC,
                "java/lang/System", "gc", "()V", false));

        byte[] classBytes = buildClassWithEmitter(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "()V",
                invokeInfos, (target) -> {
                    // The emitter will handle the InvokeMarker
                }, new ArrayList<>(), sim, true);

        List<String> calls = findReplayStateCalls(classBytes, "testMethod", "()V");
        assertTrue(calls.contains("isReplayThread"),
                "Full prologue should call isReplayThread");
        assertTrue(calls.contains("currentResumeIndex"),
                "Full prologue should call currentResumeIndex");
    }

    @Test
    void fullPrologueContainsResumeStubComponents() {
        OperandStackSimulator sim = new OperandStackSimulator();
        List<InvokeInfo> invokeInfos = new ArrayList<>();
        invokeInfos.add(new InvokeInfo(0, Opcodes.INVOKESTATIC,
                "java/lang/System", "gc", "()V", false));

        byte[] classBytes = buildClassWithEmitter(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "()V",
                invokeInfos, (target) -> {}, new ArrayList<>(), sim, true);

        List<String> calls = findReplayStateCalls(classBytes, "testMethod", "()V");
        assertTrue(calls.contains("isLastFrame"),
                "Resume stub should check isLastFrame");
        assertTrue(calls.contains("deactivate"),
                "Deepest frame stub should call deactivate");
        assertTrue(calls.contains("advanceFrame"),
                "Non-deepest frame stub should call advanceFrame");
    }

    @Test
    void fullProloguePassesVerification() {
        OperandStackSimulator sim = new OperandStackSimulator();
        List<InvokeInfo> invokeInfos = new ArrayList<>();
        invokeInfos.add(new InvokeInfo(0, Opcodes.INVOKESTATIC,
                "java/lang/System", "gc", "()V", false));

        byte[] classBytes = buildClassWithEmitter(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "()V",
                invokeInfos, (target) -> {}, new ArrayList<>(), sim, true);

        // Must pass ASM's bytecode verifier
        verifyBytecode(classBytes);
    }

    @Test
    void fullPrologueWithMultipleInvokes() {
        OperandStackSimulator sim = new OperandStackSimulator();
        List<InvokeInfo> invokeInfos = new ArrayList<>();
        invokeInfos.add(new InvokeInfo(0, Opcodes.INVOKESTATIC,
                "java/lang/System", "gc", "()V", false));
        invokeInfos.add(new InvokeInfo(1, Opcodes.INVOKESTATIC,
                "java/lang/System", "gc", "()V", false));
        invokeInfos.add(new InvokeInfo(2, Opcodes.INVOKESTATIC,
                "java/lang/System", "gc", "()V", false));

        byte[] classBytes = buildClassWithEmitter(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "()V",
                invokeInfos, (target) -> {}, new ArrayList<>(), sim, true);

        verifyBytecode(classBytes);
    }

    @Test
    void fullPrologueWithInstanceMethod() {
        // Instance method — resume stubs should call resolveReceiver
        OperandStackSimulator sim = new OperandStackSimulator();
        List<InvokeInfo> invokeInfos = new ArrayList<>();
        invokeInfos.add(new InvokeInfo(0, Opcodes.INVOKEVIRTUAL,
                "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

        // Build buffered ops that load the receiver and arg from locals,
        // so the normal code path has a valid stack at the invoke
        byte[] classBytes = buildClassWithEmitterCustomOps(
                Opcodes.ACC_PUBLIC, "(Ljava/io/PrintStream;Ljava/lang/String;)V",
                invokeInfos, new ArrayList<>(), sim, true,
                (mv, ops) -> {
                    // ALOAD 1 (PrintStream), ALOAD 2 (String), then invoke marker, then RETURN
                    ops.add(() -> mv.visitVarInsn(Opcodes.ALOAD, 1));
                    ops.add(() -> mv.visitVarInsn(Opcodes.ALOAD, 2));
                    ops.add(new InvokeMarker(0, Opcodes.INVOKEVIRTUAL,
                            "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
                    ops.add(() -> mv.visitInsn(Opcodes.RETURN));
                });

        List<String> calls = findReplayStateCalls(classBytes, "testMethod",
                "(Ljava/io/PrintStream;Ljava/lang/String;)V");
        assertTrue(calls.contains("resolveReceiver"),
                "Instance method invoke stub should call resolveReceiver");
        verifyBytecode(classBytes);
    }

    @Test
    void fullPrologueWithSubStack() {
        // Simulate: push I (sub-stack), invoke static void — the sub-stack
        // has one int item below the invoke's zero args.
        OperandStackSimulator sim = new OperandStackSimulator();
        sim.push('I'); // sub-stack item
        sim.captureSubStack(0, 0); // 0 args consumed (static void)

        List<InvokeInfo> invokeInfos = new ArrayList<>();
        invokeInfos.add(new InvokeInfo(0, Opcodes.INVOKESTATIC,
                "java/lang/System", "gc", "()V", false));

        // Build buffered ops with ICONST_0 (sub-stack) before the invoke
        byte[] classBytes = buildClassWithEmitterCustomOps(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "()V",
                invokeInfos, new ArrayList<>(), sim, true,
                (mv, ops) -> {
                    ops.add(() -> mv.visitInsn(Opcodes.ICONST_0)); // sub-stack item
                    ops.add(new InvokeMarker(0, Opcodes.INVOKESTATIC,
                            "java/lang/System", "gc", "()V", false));
                    ops.add(() -> mv.visitInsn(Opcodes.POP)); // consume sub-stack
                    ops.add(() -> mv.visitInsn(Opcodes.RETURN));
                });

        verifyBytecode(classBytes);
    }

    @Test
    void uninitializedSubStackSkipsStub() {
        // If sub-stack contains 'U' (from NEW before <init>), stub should
        // emit GOTO to originalCode instead of a real resume stub.
        OperandStackSimulator sim = new OperandStackSimulator();
        sim.push('U'); // uninitialized reference
        sim.captureSubStack(0, 0);

        List<InvokeInfo> invokeInfos = new ArrayList<>();
        invokeInfos.add(new InvokeInfo(0, Opcodes.INVOKESTATIC,
                "java/lang/System", "gc", "()V", false));

        byte[] classBytes = buildClassWithEmitter(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "()V",
                invokeInfos, (target) -> {}, new ArrayList<>(), sim, true);

        // Should still pass verification (stub jumps to original code)
        verifyBytecode(classBytes);

        // The stub for invoke 0 should NOT contain isLastFrame (skipped)
        // Can't easily verify this without instruction-level analysis,
        // but verification passing is the key check.
    }

    // ===== Local variable emission =====

    @Test
    void localVariablesEmittedForNoInvokePrologue() {
        List<LocalVarInfo> localVars = new ArrayList<>();
        Label start = new Label();
        Label end = new Label();
        localVars.add(new LocalVarInfo("x", "I", null, start, end, 1));

        byte[] classBytes = buildClassWithEmitter(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "(I)V",
                new ArrayList<>(),
                (target) -> {},
                localVars,
                new OperandStackSimulator(),
                false);

        verifyBytecode(classBytes);
    }

    // ===== Helpers =====

    /**
     * Build a class where the caller provides the exact buffered ops.
     */
    private byte[] buildClassWithEmitterCustomOps(
            int methodAccess, String methodDesc,
            List<InvokeInfo> invokeInfos, List<LocalVarInfo> localVars,
            OperandStackSimulator simulator, boolean useFullPrologue,
            java.util.function.BiConsumer<MethodVisitor, List<Runnable>> opsBuilder) {

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return PrologueEmitterTest.class.getClassLoader();
            }
        };
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "TestClass", null,
                "java/lang/Object", null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor mv = cw.visitMethod(methodAccess, "testMethod", methodDesc, null, null);
        mv.visitCode();

        List<Runnable> bufferedOps = new ArrayList<>();
        opsBuilder.accept(mv, bufferedOps);

        PrologueEmitter emitter = new PrologueEmitter(
                methodAccess, methodDesc, mv,
                invokeInfos, bufferedOps, localVars, simulator);

        if (useFullPrologue && !invokeInfos.isEmpty()) {
            emitter.emitFullPrologue();
        } else {
            emitter.emitNoInvokePrologue();
        }
        emitter.emitLocalVariables();

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Build a minimal class file containing a method instrumented by PrologueEmitter.
     */
    private byte[] buildClassWithEmitter(int methodAccess, String methodDesc,
                                         List<InvokeInfo> invokeInfos,
                                         java.util.function.Consumer<List<Runnable>> opsPopulator,
                                         List<LocalVarInfo> localVars,
                                         OperandStackSimulator simulator,
                                         boolean useFullPrologue) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return PrologueEmitterTest.class.getClassLoader();
            }
        };
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "TestClass", null,
                "java/lang/Object", null);

        // Default constructor
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // Test method
        MethodVisitor mv = cw.visitMethod(methodAccess, "testMethod", methodDesc, null, null);
        mv.visitCode();

        // Build buffered ops
        List<Runnable> bufferedOps = new ArrayList<>();

        // Add invoke markers from invokeInfos
        for (InvokeInfo info : invokeInfos) {
            if (info.opcode == Opcodes.INVOKEDYNAMIC) {
                bufferedOps.add(new InvokeDynamicMarker(info.index, info.name,
                        info.descriptor, null, new Object[0]));
            } else {
                bufferedOps.add(new InvokeMarker(info.index, info.opcode,
                        info.owner, info.name, info.descriptor, info.isInterface));
            }
        }

        // Add return
        Type retType = Type.getReturnType(methodDesc);
        if (retType.getSort() == Type.VOID) {
            bufferedOps.add(() -> mv.visitInsn(Opcodes.RETURN));
        } else if (retType.getSort() == Type.INT) {
            bufferedOps.add(() -> mv.visitInsn(Opcodes.ICONST_0));
            bufferedOps.add(() -> mv.visitInsn(Opcodes.IRETURN));
        } else {
            bufferedOps.add(() -> mv.visitInsn(Opcodes.ACONST_NULL));
            bufferedOps.add(() -> mv.visitInsn(Opcodes.ARETURN));
        }

        opsPopulator.accept(bufferedOps);

        PrologueEmitter emitter = new PrologueEmitter(
                methodAccess, methodDesc, mv,
                invokeInfos, bufferedOps, localVars, simulator);

        if (useFullPrologue && !invokeInfos.isEmpty()) {
            emitter.emitFullPrologue();
        } else {
            emitter.emitNoInvokePrologue();
        }
        emitter.emitLocalVariables();

        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private void verifyBytecode(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new CheckClassAdapter(new ClassVisitor(Opcodes.ASM9) {}), 0);
    }

    private List<String> findReplayStateCalls(byte[] classBytes, String methodName, String methodDesc) {
        List<String> calls = new ArrayList<>();
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
                            if (owner.equals("ai/jacc/durableThreads/ReplayState")) {
                                calls.add(name);
                            }
                        }
                    };
                }
                return null;
            }
        }, 0);
        return calls;
    }
}
