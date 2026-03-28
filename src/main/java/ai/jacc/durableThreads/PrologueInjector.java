package ai.jacc.durableThreads;

import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;

import ai.jacc.durableThreads.PrologueTypes.*;

/**
 * ASM-based bytecode transformer that injects the universal replay prologue.
 *
 * <h3>Architecture (Direct-Jump / Single-Pass Restore)</h3>
 * <pre>
 * METHOD ENTRY:
 *   if (!isReplayThread()) goto ORIGINAL_CODE
 *   switch (currentResumeIndex()):
 *     case N: goto RESUME_N
 *
 * RESUME_N (deepest frame):
 *   initLocalDefaults()
 *   deactivate()                         // exit replay mode
 *   pushSubStackDefaults + dummyArgs
 *   goto BEFORE_INVOKE_N                 // jump to original code — calls freeze()
 *
 * RESUME_N (non-deepest frame):
 *   advanceFrame()
 *   initLocalDefaults()
 *   pushSubStackDefaults + dummyArgs
 *   goto BEFORE_INVOKE_N                 // jump to original code — calls deeper method
 *
 * ORIGINAL_CODE:
 *   ... original bytecode ...
 *   BEFORE_INVOKE_0:
 *   invoke 0
 *   POST_INVOKE_0:
 *   ...
 * </pre>
 */
public final class PrologueInjector extends ClassVisitor {

    private String className;
    /** method key ("name+desc") → number of original invokes that received indices. */
    private final java.util.Map<String, Integer> invokeCountsByMethod = new java.util.HashMap<>();

    public PrologueInjector(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    /**
     * Returns the number of original invokes that PrologueInjector assigned
     * indices to in the given method.
     */
    public int getOriginalInvokeCount(String methodName, String methodDesc) {
        Integer n = invokeCountsByMethod.get(methodName + methodDesc);
        return n != null ? n : 0;
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

        return new PrologueMethodVisitor(access, name, descriptor, className, mv,
                invokeCountsByMethod);
    }

    /**
     * Buffers all original bytecode, simulates the operand stack, then emits
     * the replay prologue followed by the original code via {@link PrologueEmitter}.
     */
    private static class PrologueMethodVisitor extends MethodVisitor {

        private final int methodAccess;
        private final String methodName;
        private final String methodDesc;
        private final String className;
        private final MethodVisitor target;
        private final java.util.Map<String, Integer> invokeCountsOut;

        private final List<InvokeInfo> invokeInfos = new ArrayList<>();
        private final List<Runnable> bufferedOps = new ArrayList<>();
        private final List<LocalVarInfo> localVars = new ArrayList<>();
        private final OperandStackSimulator simulator = new OperandStackSimulator();

        private int invokeCounter = 0;
        private boolean hasCode = false;

        PrologueMethodVisitor(int access, String name, String desc, String className,
                              MethodVisitor target,
                              java.util.Map<String, Integer> invokeCountsOut) {
            super(Opcodes.ASM9, null);
            this.methodAccess = access;
            this.methodName = name;
            this.methodDesc = desc;
            this.className = className;
            this.target = target;
            this.invokeCountsOut = invokeCountsOut;
        }

        // --- Buffering + stack simulation ---

        @Override public void visitCode() { hasCode = true; }

        @Override public void visitInsn(int opcode) {
            simulator.simulateInsn(opcode);
            bufferedOps.add(() -> target.visitInsn(opcode));
        }

        @Override public void visitIntInsn(int opcode, int operand) {
            simulator.simulateIntInsn(opcode);
            bufferedOps.add(() -> target.visitIntInsn(opcode, operand));
        }

        @Override public void visitVarInsn(int opcode, int varIndex) {
            simulator.simulateVarInsn(opcode);
            // Record store instructions for scope map building
            switch (opcode) {
                case Opcodes.ISTORE: case Opcodes.LSTORE: case Opcodes.FSTORE:
                case Opcodes.DSTORE: case Opcodes.ASTORE:
                    bufferedOps.add(new StoreRecord(opcode, varIndex));
                    break;
            }
            bufferedOps.add(() -> target.visitVarInsn(opcode, varIndex));
        }

        @Override public void visitTypeInsn(int opcode, String type) {
            simulator.simulateTypeInsn(opcode);
            bufferedOps.add(() -> target.visitTypeInsn(opcode, type));
        }

        @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            simulator.simulateFieldInsn(opcode, desc);
            bufferedOps.add(() -> target.visitFieldInsn(opcode, owner, name, desc));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                                    boolean isInterface) {
            boolean isConstructorCall = (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name));

            simulator.simulateMethodInsn(
                    isConstructorCall ? -1 : invokeCounter,
                    opcode, desc, isConstructorCall);

            if (isConstructorCall) {
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
            simulator.simulateInvokeDynamic(invokeCounter, desc);
            int idx = invokeCounter++;
            invokeInfos.add(new InvokeInfo(idx, Opcodes.INVOKEDYNAMIC, null, name, desc, false));
            bufferedOps.add(new InvokeDynamicMarker(idx, name, desc, bsmHandle, bsmArgs));
        }

        @Override public void visitJumpInsn(int opcode, Label label) {
            simulator.simulateJumpInsn(opcode, label);
            bufferedOps.add(() -> target.visitJumpInsn(opcode, label));
        }

        @Override public void visitLabel(Label label) {
            simulator.visitLabel(label);
            bufferedOps.add(new LabelOp(label, target));
        }

        @Override public void visitLdcInsn(Object value) {
            simulator.simulateLdc(value);
            bufferedOps.add(() -> target.visitLdcInsn(value));
        }

        @Override public void visitIincInsn(int varIndex, int increment) {
            // No stack change
            bufferedOps.add(() -> target.visitIincInsn(varIndex, increment));
        }

        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            simulator.simulateTableSwitch(dflt, labels);
            bufferedOps.add(() -> target.visitTableSwitchInsn(min, max, dflt, labels));
        }

        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            simulator.simulateLookupSwitch(dflt, labels);
            bufferedOps.add(() -> target.visitLookupSwitchInsn(dflt, keys, labels));
        }

        @Override public void visitMultiANewArrayInsn(String desc, int numDimensions) {
            simulator.simulateMultiANewArray(numDimensions);
            bufferedOps.add(() -> target.visitMultiANewArrayInsn(desc, numDimensions));
        }

        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            simulator.simulateTryCatchBlock(handler);
            bufferedOps.add(() -> target.visitTryCatchBlock(start, end, handler, type));
        }

        @Override public void visitLocalVariable(String name, String desc, String sig,
                                                  Label start, Label end, int index) {
            localVars.add(new LocalVarInfo(name, desc, sig, start, end, index));
        }

        @Override public void visitLineNumber(int line, Label start) {
            bufferedOps.add(() -> target.visitLineNumber(line, start));
        }

        @Override public void visitFrame(int type, int numLocal, Object[] local,
                                          int numStack, Object[] stack) {
            // COMPUTE_FRAMES recomputes all — skip
        }

        @Override public void visitMaxs(int maxStack, int maxLocals) {
            // Captured but unused — COMPUTE_FRAMES recalculates
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
            invokeCountsOut.put(methodName + methodDesc, invokeCounter);

            if (!hasCode) {
                target.visitEnd();
                return;
            }

            target.visitCode();

            PrologueEmitter emitter = new PrologueEmitter(
                    methodAccess, methodDesc, target,
                    invokeInfos, bufferedOps, localVars, simulator);

            if (invokeInfos.isEmpty()) {
                emitter.emitNoInvokePrologue();
            } else {
                emitter.emitFullPrologue();
            }

            emitter.emitLocalVariables();

            target.visitMaxs(0, 0);
            target.visitEnd();
        }
    }
}
