package com.u1.durableThreads;

import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;

/**
 * ASM-based bytecode transformer that injects the universal replay prologue.
 *
 * <h3>Architecture</h3>
 * <pre>
 * METHOD ENTRY:
 *   __skip = -1
 *   if (!isReplayThread()) goto SECTION_0
 *   // ... replay dispatch sets __skip = invokeIndex, goto SECTION_0 ...
 *
 * SECTION_0: (original code, running normally)
 *   ... code that pushes args for invoke 0 ...
 *   // SKIP CHECK (args are on stack):
 *   if (__skip == 0) { pop args; push dummy return; goto POST_INVOKE_0; }
 *   invoke 0
 * POST_INVOKE_0:
 *   ... code that pushes args for invoke 1 ...
 *   if (__skip == 1) { pop args; push dummy return; goto POST_INVOKE_1; }
 *   invoke 1
 * POST_INVOKE_1:
 *   ...
 * </pre>
 *
 * <p>The skip checks are placed right before each invoke (after args are pushed).
 * When skipping, we pop the invoke's arguments and push a dummy return value.
 * The original code runs normally up to each invoke, so locals are correct
 * at every merge point.</p>
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

        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return mv;
        if ("<clinit>".equals(name)) return mv;
        if ("<init>".equals(name)) return mv;

        return new PrologueMethodVisitor(access, name, descriptor, className, mv);
    }

    private static class PrologueMethodVisitor extends MethodVisitor {

        private final int methodAccess;
        private final String methodName;
        private final String methodDesc;
        private final String className;
        private final MethodVisitor target;

        private final List<InvokeInfo> invokeInfos = new ArrayList<>();
        private final List<Runnable> bufferedOps = new ArrayList<>();

        private int invokeCounter = 0;
        private boolean hasCode = false;
        private int originalMaxLocals = 0;

        PrologueMethodVisitor(int access, String name, String desc, String className,
                              MethodVisitor target) {
            super(Opcodes.ASM9, null);
            this.methodAccess = access;
            this.methodName = name;
            this.methodDesc = desc;
            this.className = className;
            this.target = target;
        }

        // --- Buffering ---

        @Override public void visitCode() { hasCode = true; }
        @Override public void visitInsn(int opcode) {
            bufferedOps.add(() -> target.visitInsn(opcode));
        }
        @Override public void visitIntInsn(int opcode, int operand) {
            bufferedOps.add(() -> target.visitIntInsn(opcode, operand));
        }
        @Override public void visitVarInsn(int opcode, int varIndex) {
            bufferedOps.add(() -> target.visitVarInsn(opcode, varIndex));
        }
        @Override public void visitTypeInsn(int opcode, String type) {
            bufferedOps.add(() -> target.visitTypeInsn(opcode, type));
        }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            bufferedOps.add(() -> target.visitFieldInsn(opcode, owner, name, desc));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                                    boolean isInterface) {
            // Skip-check can't wrap invokespecial <init> — the verifier tracks
            // uninitialized objects from NEW and requires <init> to be called on
            // all paths. A skip-check branch would leave the object uninitialized.
            boolean isConstructorCall = (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name));
            if (isConstructorCall) {
                // Emit directly, no skip-check, no invoke index
                bufferedOps.add(() -> target.visitMethodInsn(opcode, owner, name, desc, isInterface));
                return;
            }

            int idx = invokeCounter++;
            invokeInfos.add(new InvokeInfo(idx, opcode, owner, name, desc, isInterface));
            bufferedOps.add(new InvokeMarker(idx, opcode, owner, name, desc, isInterface));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc,
                                           Handle bsmHandle, Object... bsmArgs) {
            int idx = invokeCounter++;
            invokeInfos.add(new InvokeInfo(idx, Opcodes.INVOKEDYNAMIC, null, name, desc, false));
            bufferedOps.add(new InvokeDynamicMarker(idx, name, desc, bsmHandle, bsmArgs));
        }

        @Override public void visitJumpInsn(int opcode, Label label) {
            bufferedOps.add(() -> target.visitJumpInsn(opcode, label));
        }
        @Override public void visitLabel(Label label) {
            bufferedOps.add(() -> target.visitLabel(label));
        }
        @Override public void visitLdcInsn(Object value) {
            bufferedOps.add(() -> target.visitLdcInsn(value));
        }
        @Override public void visitIincInsn(int varIndex, int increment) {
            bufferedOps.add(() -> target.visitIincInsn(varIndex, increment));
        }
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            bufferedOps.add(() -> target.visitTableSwitchInsn(min, max, dflt, labels));
        }
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            bufferedOps.add(() -> target.visitLookupSwitchInsn(dflt, keys, labels));
        }
        @Override public void visitMultiANewArrayInsn(String desc, int numDimensions) {
            bufferedOps.add(() -> target.visitMultiANewArrayInsn(desc, numDimensions));
        }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            bufferedOps.add(() -> target.visitTryCatchBlock(start, end, handler, type));
        }
        @Override public void visitLocalVariable(String name, String desc, String sig,
                                                  Label start, Label end, int index) {
            bufferedOps.add(() -> target.visitLocalVariable(name, desc, sig, start, end, index));
        }
        @Override public void visitLineNumber(int line, Label start) {
            bufferedOps.add(() -> target.visitLineNumber(line, start));
        }
        @Override public void visitFrame(int type, int numLocal, Object[] local,
                                          int numStack, Object[] stack) {
            // COMPUTE_FRAMES recomputes all — skip
        }
        @Override public void visitMaxs(int maxStack, int maxLocals) {
            this.originalMaxLocals = maxLocals;
        }
        @Override public void visitParameter(String name, int access) {
            target.visitParameter(name, access);
        }
        @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            return target.visitAnnotation(desc, visible);
        }
        @Override public AnnotationVisitor visitParameterAnnotation(int param, String desc,
                                                                     boolean visible) {
            return target.visitParameterAnnotation(param, desc, visible);
        }
        @Override public void visitAttribute(Attribute attr) {
            target.visitAttribute(attr);
        }

        // --- Emission ---

        @Override
        public void visitEnd() {
            if (!hasCode) {
                target.visitEnd();
                return;
            }

            target.visitCode();
            int skipSlot = originalMaxLocals;

            if (invokeInfos.isEmpty()) {
                emitNoInvokePrologue();
            } else {
                emitFullPrologue(skipSlot);
            }

            target.visitMaxs(0, 0);
            target.visitEnd();
        }

        private void emitNoInvokePrologue() {
            Label normalCode = new Label();
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "isReplayThread", "()Z", false);
            target.visitJumpInsn(Opcodes.IFEQ, normalCode);
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "resumePoint", "()V", false);
            target.visitLabel(normalCode);
            for (Runnable op : bufferedOps) {
                op.run();
            }
        }

        private void emitFullPrologue(int skipSlot) {
            Label originalCode = new Label();

            // --- PROLOGUE ---

            // __skip = -1
            target.visitInsn(Opcodes.ICONST_M1);
            target.visitVarInsn(Opcodes.ISTORE, skipSlot);

            // Normal execution: skip straight to original code
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "isReplayThread", "()Z", false);
            target.visitJumpInsn(Opcodes.IFEQ, originalCode);

            // Replay: dispatch to resume stubs
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "currentResumeIndex", "()I", false);

            Label[] resumeLabels = new Label[invokeInfos.size()];
            for (int i = 0; i < resumeLabels.length; i++) {
                resumeLabels[i] = new Label();
            }
            target.visitTableSwitchInsn(0, invokeInfos.size() - 1, originalCode, resumeLabels);

            // --- RESUME STUBS ---
            for (int i = 0; i < invokeInfos.size(); i++) {
                target.visitLabel(resumeLabels[i]);
                emitResumeStub(i, invokeInfos.get(i), skipSlot, originalCode);
            }

            // --- ORIGINAL CODE with inline skip checks ---
            target.visitLabel(originalCode);
            emitOriginalCodeWithSkipChecks(skipSlot);
        }

        /**
         * Resume stub: rebuild call stack (or resumePoint for deepest frame),
         * set __skip = invokeIndex, goto originalCode.
         */
        private void emitResumeStub(int invokeIndex, InvokeInfo info,
                                    int skipSlot, Label originalCode) {
            Label notLastFrame = new Label();

            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "isLastFrame", "()Z", false);
            target.visitJumpInsn(Opcodes.IFEQ, notLastFrame);

            // Deepest frame: resumePoint(), set __skip, goto originalCode
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "resumePoint", "()V", false);
            emitIntConst(invokeIndex);
            target.visitVarInsn(Opcodes.ISTORE, skipSlot);
            target.visitJumpInsn(Opcodes.GOTO, originalCode);

            // Not deepest: advance, re-invoke, set __skip, goto originalCode
            target.visitLabel(notLastFrame);
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/u1/durableThreads/ReplayState", "advanceFrame", "()V", false);

            if (info.opcode == Opcodes.INVOKEDYNAMIC) {
                target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "com/u1/durableThreads/ReplayState", "resumePoint", "()V", false);
            } else {
                pushDummyArguments(info);
                target.visitMethodInsn(info.opcode, info.owner, info.name,
                        info.descriptor, info.isInterface);
                popValue(Type.getReturnType(info.descriptor));
            }

            emitIntConst(invokeIndex);
            target.visitVarInsn(Opcodes.ISTORE, skipSlot);
            target.visitJumpInsn(Opcodes.GOTO, originalCode);
        }

        /**
         * Emit original code with skip checks inserted right before each invoke.
         * When skipping: pop the invoke's arguments, push dummy return, goto postInvoke.
         * The original code runs normally up to each invoke, so locals match at merge points.
         */
        private void emitOriginalCodeWithSkipChecks(int skipSlot) {
            for (Runnable op : bufferedOps) {
                if (op instanceof InvokeMarker marker) {
                    emitInvokeWithSkipCheck(skipSlot, marker.index, marker.opcode,
                            marker.owner, marker.name, marker.descriptor, marker.isInterface);
                } else if (op instanceof InvokeDynamicMarker marker) {
                    emitInvokeDynamicWithSkipCheck(skipSlot, marker.index,
                            marker.name, marker.descriptor,
                            marker.bootstrapMethodHandle, marker.bootstrapMethodArguments);
                } else {
                    op.run();
                }
            }
        }

        /**
         * Emit:
         *   if (__skip == index) { pop receiver+args; __skip=-1; push dummy return; goto postInvoke; }
         *   invoke
         *   postInvoke:
         */
        private void emitInvokeWithSkipCheck(int skipSlot, int index, int opcode,
                                              String owner, String name, String desc,
                                              boolean isInterface) {
            Label postInvoke = new Label();
            Label doInvoke = new Label();

            // Check if we should skip this invoke
            target.visitVarInsn(Opcodes.ILOAD, skipSlot);
            emitIntConst(index);
            target.visitJumpInsn(Opcodes.IF_ICMPNE, doInvoke);

            // SKIP PATH: pop args from stack, reset __skip, push dummy return, goto postInvoke
            // Pop receiver (for non-static) and all args
            popInvokeArgs(opcode, desc);
            // Reset __skip
            target.visitInsn(Opcodes.ICONST_M1);
            target.visitVarInsn(Opcodes.ISTORE, skipSlot);
            // Push dummy return
            pushDummyReturnValue(desc);
            target.visitJumpInsn(Opcodes.GOTO, postInvoke);

            // NORMAL PATH: execute the invoke
            target.visitLabel(doInvoke);
            target.visitMethodInsn(opcode, owner, name, desc, isInterface);

            target.visitLabel(postInvoke);
        }

        private void emitInvokeDynamicWithSkipCheck(int skipSlot, int index,
                                                     String name, String desc,
                                                     Handle bsmHandle, Object[] bsmArgs) {
            Label postInvoke = new Label();
            Label doInvoke = new Label();

            target.visitVarInsn(Opcodes.ILOAD, skipSlot);
            emitIntConst(index);
            target.visitJumpInsn(Opcodes.IF_ICMPNE, doInvoke);

            // SKIP PATH
            popInvokeArgs(Opcodes.INVOKESTATIC, desc); // invokedynamic has no receiver
            target.visitInsn(Opcodes.ICONST_M1);
            target.visitVarInsn(Opcodes.ISTORE, skipSlot);
            pushDummyReturnValue(desc);
            target.visitJumpInsn(Opcodes.GOTO, postInvoke);

            // NORMAL PATH
            target.visitLabel(doInvoke);
            target.visitInvokeDynamicInsn(name, desc, bsmHandle, bsmArgs);

            target.visitLabel(postInvoke);
        }

        /**
         * Pop the receiver (for non-static) and all arguments from the stack.
         * This reverses the argument-pushing that the original code did before the invoke.
         */
        private void popInvokeArgs(int opcode, String desc) {
            // Build list of types to pop (in reverse order, since stack is LIFO)
            Type[] argTypes = Type.getArgumentTypes(desc);

            // Pop arguments (rightmost first, they're on top of stack)
            for (int i = argTypes.length - 1; i >= 0; i--) {
                popValue(argTypes[i]);
            }

            // Pop receiver for non-static invokes
            if (opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKEDYNAMIC) {
                target.visitInsn(Opcodes.POP); // receiver is always a single-slot reference
            }
        }

        // --- Helpers ---

        private void pushDummyArguments(InvokeInfo info) {
            if (info.opcode != Opcodes.INVOKESTATIC && info.opcode != Opcodes.INVOKEDYNAMIC) {
                target.visitLdcInsn(info.owner.replace('/', '.'));
                target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "com/u1/durableThreads/ReplayState", "dummyInstance",
                        "(Ljava/lang/String;)Ljava/lang/Object;", false);
                target.visitTypeInsn(Opcodes.CHECKCAST, info.owner);
            }
            for (Type argType : Type.getArgumentTypes(info.descriptor)) {
                pushDefaultValue(argType);
            }
        }

        private void pushDummyReturnValue(String desc) {
            Type retType = Type.getReturnType(desc);
            if (retType.getSort() != Type.VOID) {
                pushDefaultValue(retType);
            }
        }

        private void popValue(Type type) {
            switch (type.getSort()) {
                case Type.VOID -> {}
                case Type.LONG, Type.DOUBLE -> target.visitInsn(Opcodes.POP2);
                default -> target.visitInsn(Opcodes.POP);
            }
        }

        private void pushDefaultValue(Type type) {
            switch (type.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT ->
                        target.visitInsn(Opcodes.ICONST_0);
                case Type.LONG -> target.visitInsn(Opcodes.LCONST_0);
                case Type.FLOAT -> target.visitInsn(Opcodes.FCONST_0);
                case Type.DOUBLE -> target.visitInsn(Opcodes.DCONST_0);
                case Type.ARRAY, Type.OBJECT -> target.visitInsn(Opcodes.ACONST_NULL);
                default -> {}
            }
        }

        private void emitIntConst(int value) {
            if (value >= -1 && value <= 5) {
                target.visitInsn(Opcodes.ICONST_0 + value);
            } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                target.visitIntInsn(Opcodes.BIPUSH, value);
            } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                target.visitIntInsn(Opcodes.SIPUSH, value);
            } else {
                target.visitLdcInsn(value);
            }
        }

        // --- Marker types ---

        record InvokeMarker(int index, int opcode, String owner, String name,
                            String descriptor, boolean isInterface) implements Runnable {
            @Override public void run() {
                throw new IllegalStateException("InvokeMarker must be handled");
            }
        }

        record InvokeDynamicMarker(int index, String name, String descriptor,
                                   Handle bootstrapMethodHandle,
                                   Object[] bootstrapMethodArguments) implements Runnable {
            @Override public void run() {
                throw new IllegalStateException("InvokeDynamicMarker must be handled");
            }
        }
    }

    record InvokeInfo(int index, int opcode, String owner, String name, String descriptor,
                      boolean isInterface) {}
}
