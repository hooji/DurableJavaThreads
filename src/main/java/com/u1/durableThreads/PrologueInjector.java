package com.u1.durableThreads;

import com.u1.durableThreads.internal.InvokeRegistry;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AnalyzerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * ASM-based bytecode transformer that injects the universal replay prologue
 * into every method.
 *
 * <p>The prologue adds a single branch check at method entry: "Am I the replay thread?"
 * During normal execution this is a not-taken branch with zero effective overhead.
 * During replay, it dispatches to resume stubs that reconstruct the call stack.</p>
 *
 * <p>For each invoke instruction in the original method, a resume stub is generated
 * that can re-enter the call chain at that point.</p>
 */
public final class PrologueInjector extends ClassVisitor {

    private String className;

    public PrologueInjector(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (mv == null) return null;

        // Skip abstract and native methods (no bytecode to instrument)
        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return mv;
        }

        // Skip class initializers — they can't be meaningfully replayed
        if ("<clinit>".equals(name)) {
            return mv;
        }

        // Two-pass approach:
        // We need to know invoke locations before generating the prologue.
        // Use a deferred method visitor that buffers the original method,
        // then emits prologue + original code.
        return new PrologueMethodVisitor(access, name, descriptor, className, mv);
    }

    /**
     * Method visitor that collects invoke instruction info on first pass,
     * then generates the prologue and replays the original instructions.
     */
    private static class PrologueMethodVisitor extends MethodVisitor {

        private final int methodAccess;
        private final String methodName;
        private final String methodDesc;
        private final String className;
        private final MethodVisitor target;

        // Collected invoke instructions (in order of appearance)
        private final List<InvokeInfo> invokeInfos = new ArrayList<>();

        // Labels placed after each invoke instruction in the original code
        private final List<Label> afterInvokeLabels = new ArrayList<>();

        // We buffer all method visit calls and replay them after the prologue
        private final List<Runnable> bufferedOps = new ArrayList<>();

        // Counter for invoke instructions encountered
        private int invokeCounter = 0;

        // Track if the method has any code at all
        private boolean hasCode = false;

        PrologueMethodVisitor(int access, String name, String desc, String className,
                              MethodVisitor target) {
            // Pass null as delegate — we buffer everything ourselves
            super(Opcodes.ASM9, null);
            this.methodAccess = access;
            this.methodName = name;
            this.methodDesc = desc;
            this.className = className;
            this.target = target;
        }

        // --- Buffering all visit calls ---

        @Override
        public void visitCode() {
            hasCode = true;
            bufferedOps.add(() -> {});  // visitCode emitted by us
        }

        @Override
        public void visitInsn(int opcode) {
            bufferedOps.add(() -> target.visitInsn(opcode));
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            bufferedOps.add(() -> target.visitIntInsn(opcode, operand));
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            bufferedOps.add(() -> target.visitVarInsn(opcode, varIndex));
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            bufferedOps.add(() -> target.visitTypeInsn(opcode, type));
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            bufferedOps.add(() -> target.visitFieldInsn(opcode, owner, name, descriptor));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            int idx = invokeCounter++;
            Label afterLabel = new Label();
            afterInvokeLabels.add(afterLabel);
            invokeInfos.add(new InvokeInfo(opcode, owner, name, descriptor, isInterface));

            // Buffer: emit the original invoke, then place label after it
            bufferedOps.add(() -> {
                target.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                target.visitLabel(afterLabel);
            });
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
                                           Handle bootstrapMethodHandle,
                                           Object... bootstrapMethodArguments) {
            int idx = invokeCounter++;
            Label afterLabel = new Label();
            afterInvokeLabels.add(afterLabel);
            invokeInfos.add(new InvokeInfo(Opcodes.INVOKEDYNAMIC, null, name, descriptor, false));

            bufferedOps.add(() -> {
                target.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle,
                        bootstrapMethodArguments);
                target.visitLabel(afterLabel);
            });
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            bufferedOps.add(() -> target.visitJumpInsn(opcode, label));
        }

        @Override
        public void visitLabel(Label label) {
            bufferedOps.add(() -> target.visitLabel(label));
        }

        @Override
        public void visitLdcInsn(Object value) {
            bufferedOps.add(() -> target.visitLdcInsn(value));
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            bufferedOps.add(() -> target.visitIincInsn(varIndex, increment));
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            bufferedOps.add(() -> target.visitTableSwitchInsn(min, max, dflt, labels));
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            bufferedOps.add(() -> target.visitLookupSwitchInsn(dflt, keys, labels));
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            bufferedOps.add(() -> target.visitMultiANewArrayInsn(descriptor, numDimensions));
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            bufferedOps.add(() -> target.visitTryCatchBlock(start, end, handler, type));
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       Label start, Label end, int index) {
            bufferedOps.add(() -> target.visitLocalVariable(name, descriptor, signature,
                    start, end, index));
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            bufferedOps.add(() -> target.visitLineNumber(line, start));
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local,
                               int numStack, Object[] stack) {
            bufferedOps.add(() -> target.visitFrame(type, numLocal, local, numStack, stack));
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // We'll let ASM compute this
            bufferedOps.add(() -> target.visitMaxs(maxStack, maxLocals));
        }

        @Override
        public void visitParameter(String name, int access) {
            // Parameters are emitted before code, pass through directly
            target.visitParameter(name, access);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor,
                                                          boolean visible) {
            return target.visitParameterAnnotation(parameter, descriptor, visible);
        }

        @Override
        public void visitAttribute(Attribute attribute) {
            target.visitAttribute(attribute);
        }

        @Override
        public void visitEnd() {
            if (!hasCode) {
                target.visitEnd();
                return;
            }

            target.visitCode();

            if (invokeInfos.isEmpty()) {
                // No invoke instructions — just emit the original code with a trivial prologue
                // (the check is still present for consistency, but there are no resume stubs)
                Label originalStart = new Label();

                // Replay check
                target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "com/u1/durableThreads/ReplayState", "isReplayThread", "()Z", false);
                target.visitJumpInsn(Opcodes.IFEQ, originalStart);
                // No resume stubs — if somehow we're replaying through a method with no invokes,
                // just call resumePoint and fall through
                target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "com/u1/durableThreads/ReplayState", "resumePoint", "()V", false);

                target.visitLabel(originalStart);
                // Frame hint for the verifier
                target.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

                // Emit original code
                for (Runnable op : bufferedOps) {
                    op.run();
                }
            } else {
                emitPrologueAndCode();
            }

            target.visitEnd();
        }

        private void emitPrologueAndCode() {
            Label originalStart = new Label();

            // === PROLOGUE START ===

            // Check: is current thread the replay thread?
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "isReplayThread", "()Z", false);
            target.visitJumpInsn(Opcodes.IFEQ, originalStart);

            // Get resume index for this frame
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "currentResumeIndex", "()I", false);

            // Dispatch to resume stubs via tableswitch
            Label[] resumeLabels = new Label[invokeInfos.size()];
            for (int i = 0; i < resumeLabels.length; i++) {
                resumeLabels[i] = new Label();
            }
            target.visitTableSwitchInsn(0, invokeInfos.size() - 1, originalStart, resumeLabels);

            // === RESUME STUBS ===
            for (int i = 0; i < invokeInfos.size(); i++) {
                target.visitLabel(resumeLabels[i]);
                // Frame hint for verifier
                target.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

                emitResumeStub(i, invokeInfos.get(i), afterInvokeLabels.get(i));
            }

            // === ORIGINAL CODE ===
            target.visitLabel(originalStart);
            target.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

            for (Runnable op : bufferedOps) {
                op.run();
            }
        }

        private void emitResumeStub(int invokeIndex, InvokeInfo info, Label afterInvoke) {
            Label notLastFrame = new Label();

            // Check if this is the deepest frame
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "isLastFrame", "()Z", false);
            target.visitJumpInsn(Opcodes.IFEQ, notLastFrame);

            // === DEEPEST FRAME: call resumePoint() then goto after the invoke ===
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "resumePoint", "()V", false);

            // Discard the invoke's return value — we need to push a dummy if the
            // original invoke had a non-void return type
            pushDummyReturnValue(info);

            target.visitJumpInsn(Opcodes.GOTO, afterInvoke);

            // === NOT DEEPEST FRAME: advance and re-invoke ===
            target.visitLabel(notLastFrame);
            target.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

            // Advance to next frame
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "advanceFrame", "()V", false);

            // Push dummy arguments for the invoke
            pushDummyArguments(info);

            // Re-invoke the method (this will trigger the next method's prologue)
            if (info.opcode == Opcodes.INVOKEDYNAMIC) {
                // For invokedynamic, we can't easily re-invoke.
                // Fall back to resumePoint for now.
                target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "com/u1/durableThreads/ReplayState", "resumePoint", "()V", false);
                pushDummyReturnValue(info);
            } else {
                target.visitMethodInsn(info.opcode, info.owner, info.name, info.descriptor,
                        info.isInterface);
            }

            // After the invoke returns (stack is rebuilt), jump past the original invoke
            // into the original code
            target.visitJumpInsn(Opcodes.GOTO, afterInvoke);
        }

        /**
         * Push dummy (type-correct) arguments for an invoke instruction.
         * For virtual/interface calls, this includes a dummy receiver.
         */
        private void pushDummyArguments(InvokeInfo info) {
            // For virtual, special, and interface calls: push a dummy receiver
            if (info.opcode != Opcodes.INVOKESTATIC && info.opcode != Opcodes.INVOKEDYNAMIC) {
                // Push a dummy receiver. We use ReplayState.dummyInstance() to get a non-null
                // object of the right type (needed to avoid NPE on invokevirtual).
                target.visitLdcInsn(info.owner.replace('/', '.'));
                target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "com/u1/durableThreads/ReplayState", "dummyInstance",
                        "(Ljava/lang/String;)Ljava/lang/Object;", false);
                target.visitTypeInsn(Opcodes.CHECKCAST, info.owner);
            }

            // Push dummy values for each parameter
            Type[] argTypes = Type.getArgumentTypes(info.descriptor);
            for (Type argType : argTypes) {
                pushDefaultValue(argType);
            }
        }

        /**
         * After resumePoint() returns at the deepest frame, we may need a dummy
         * return value on the stack if the original invoke was non-void.
         */
        private void pushDummyReturnValue(InvokeInfo info) {
            Type returnType = Type.getReturnType(info.descriptor);
            if (returnType.getSort() != Type.VOID) {
                pushDefaultValue(returnType);
            }
        }

        /**
         * Push a default/zero value for the given type.
         */
        private void pushDefaultValue(Type type) {
            switch (type.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT ->
                        target.visitInsn(Opcodes.ICONST_0);
                case Type.LONG -> target.visitInsn(Opcodes.LCONST_0);
                case Type.FLOAT -> target.visitInsn(Opcodes.FCONST_0);
                case Type.DOUBLE -> target.visitInsn(Opcodes.DCONST_0);
                case Type.ARRAY, Type.OBJECT -> target.visitInsn(Opcodes.ACONST_NULL);
                default -> {} // VOID — nothing to push
            }
        }
    }

    /** Information about a single invoke instruction. */
    record InvokeInfo(int opcode, String owner, String name, String descriptor,
                      boolean isInterface) {}
}
