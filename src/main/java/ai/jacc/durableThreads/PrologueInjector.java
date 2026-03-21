package ai.jacc.durableThreads;

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
 *   if (!isReplayThread()) goto ORIGINAL_CODE
 *   switch (currentResumeIndex()):
 *     case N: goto RESUME_N
 *
 * RESUME_N:
 *   if (isLastFrame) { resumePoint(); } else { advanceFrame(); invoke next; }
 *   __skip = N
 *   goto ORIGINAL_CODE
 *
 * ORIGINAL_CODE:
 *   ... original bytecode ...
 *   if (__skip &gt;= 0) { pop args; skip invoke 0 }
 *   invoke 0
 *   POST_INVOKE_0:
 *   ...
 *   if (__skip &gt;= 1) { pop args; [if target: __skip=-1, localsReady()] skip invoke 1 }
 *   invoke 1
 *   POST_INVOKE_1:
 *   ...
 * </pre>
 *
 * <p>During replay, __skip is set to the target invoke index. ALL invokes from
 * index 0 through __skip are skipped (no side effects from println, I/O, etc.).
 * When the target invoke itself is skipped, {@code ReplayState.localsReady()}
 * is called to block until the JDI worker sets local variables. Then execution
 * continues past the freeze point with correct locals.</p>
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
     * skip-check indices to in the given method.  This is the authoritative
     * count — the raw bytecode scanner may see additional invokes in resume
     * stubs, but only the last {@code getOriginalInvokeCount()} entries in
     * the scanner's list correspond to the original code.
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

        private int invokeCounter = 0;
        private boolean hasCode = false;
        private int originalMaxLocals = 0;

        // Method-wide labels for extending local variable scope.
        // By extending scope to cover resume stubs, JDI can set parameters
        // at any BCI (needed for Phase 1 of two-phase restore).
        private Label methodStartLabel;
        private Label methodEndLabel;

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
            // Collect for deduplication — emitted with method-wide scope at the end
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
            // Record the count before emitting — this is the authoritative number
            // of original-code invokes that received skip-check indices.
            invokeCountsOut.put(methodName + methodDesc, invokeCounter);

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

            // Emit deduplicated local variables with method-wide scope
            emitLocalVariables();

            target.visitMaxs(0, 0);
            target.visitEnd();
        }

        /**
         * Emit local variable debug info. Method parameters get method-wide scope
         * so JDI can set them at resume stub BCIs (Phase 1 of two-phase restore).
         * Non-parameter locals keep their original scope to avoid InconsistentDebugInfoException.
         */
        private void emitLocalVariables() {
            if (methodStartLabel == null || methodEndLabel == null) {
                // No prologue was emitted — emit original entries unchanged
                for (LocalVarInfo lv : localVars) {
                    target.visitLocalVariable(lv.name(), lv.desc(), lv.sig(),
                            lv.start(), lv.end(), lv.index());
                }
                return;
            }

            // Compute parameter slot count from method descriptor
            int paramSlots = 0;
            if ((methodAccess & Opcodes.ACC_STATIC) == 0) {
                paramSlots = 1; // 'this'
            }
            for (Type t : Type.getArgumentTypes(methodDesc)) {
                paramSlots += t.getSize();
            }

            // Extend scope only for parameter slots; deduplicate to avoid
            // Duplicated LocalVariableTable errors from if/else branches
            java.util.Set<String> seenParams = new java.util.HashSet<>();
            for (LocalVarInfo lv : localVars) {
                if (lv.index() < paramSlots) {
                    String key = lv.name() + "\0" + lv.desc() + "\0" + lv.index();
                    if (seenParams.add(key)) {
                        target.visitLocalVariable(lv.name(), lv.desc(), lv.sig(),
                                methodStartLabel, methodEndLabel, lv.index());
                    }
                } else {
                    // Non-parameter: keep original scope
                    target.visitLocalVariable(lv.name(), lv.desc(), lv.sig(),
                            lv.start(), lv.end(), lv.index());
                }
            }
        }

        private void emitNoInvokePrologue() {
            methodStartLabel = new Label();
            methodEndLabel = new Label();
            target.visitLabel(methodStartLabel);

            Label normalCode = new Label();
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "isReplayThread", "()Z", false);
            target.visitJumpInsn(Opcodes.IFEQ, normalCode);
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "resumePoint", "()V", false);
            target.visitLabel(normalCode);
            for (Runnable op : bufferedOps) {
                op.run();
            }

            target.visitLabel(methodEndLabel);
        }

        private void emitFullPrologue(int skipSlot) {
            int retValSlot = skipSlot + 1; // Object slot for boxed return values
            Label originalCode = new Label();

            // Method-wide labels for extending local variable scope
            methodStartLabel = new Label();
            methodEndLabel = new Label();
            target.visitLabel(methodStartLabel);

            // --- PROLOGUE ---

            // __skip = -1
            target.visitInsn(Opcodes.ICONST_M1);
            target.visitVarInsn(Opcodes.ISTORE, skipSlot);

            // __retVal = null (boxed return value from resume stub)
            target.visitInsn(Opcodes.ACONST_NULL);
            target.visitVarInsn(Opcodes.ASTORE, retValSlot);

            // Normal execution: skip straight to original code
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "isReplayThread", "()Z", false);
            target.visitJumpInsn(Opcodes.IFEQ, originalCode);

            // Replay: dispatch to resume stubs
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "currentResumeIndex", "()I", false);

            Label[] resumeLabels = new Label[invokeInfos.size()];
            for (int i = 0; i < resumeLabels.length; i++) {
                resumeLabels[i] = new Label();
            }
            target.visitTableSwitchInsn(0, invokeInfos.size() - 1, originalCode, resumeLabels);

            // --- RESUME STUBS ---
            for (int i = 0; i < invokeInfos.size(); i++) {
                target.visitLabel(resumeLabels[i]);
                emitResumeStub(i, invokeInfos.get(i), skipSlot, retValSlot, originalCode);
            }

            // --- ORIGINAL CODE with inline skip checks ---
            target.visitLabel(originalCode);
            emitOriginalCodeWithSkipChecks(skipSlot, retValSlot);

            // End label for method-wide local variable scope
            target.visitLabel(methodEndLabel);
        }

        /**
         * Resume stub: rebuild call stack (or resumePoint for deepest frame),
         * set __skip = invokeIndex, goto originalCode.
         *
         * <p>After resumePoint() returns, the resume stub sets __skip and jumps
         * to the original code. The original code re-executes but ALL invokes
         * from index 0 through __skip are skipped (no side effects). When the
         * target invoke is reached, localsReady() blocks for JDI to set locals.</p>
         *
         * <p>For non-deepest frames, the resume stub re-invokes the inner method
         * and boxes its return value into {@code retValSlot}. The skip path in
         * the original code then unboxes it instead of pushing a dummy default,
         * preserving return value propagation across the call chain.</p>
         */
        private void emitResumeStub(int invokeIndex, InvokeInfo info,
                                    int skipSlot, int retValSlot, Label originalCode) {
            Label notLastFrame = new Label();

            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "isLastFrame", "()Z", false);
            target.visitJumpInsn(Opcodes.IFEQ, notLastFrame);

            // Deepest frame: resumePoint(), set __skip, goto originalCode
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "resumePoint", "()V", false);
            emitIntConst(invokeIndex);
            target.visitVarInsn(Opcodes.ISTORE, skipSlot);
            target.visitJumpInsn(Opcodes.GOTO, originalCode);

            // Not deepest: advance, re-invoke, box return value, set __skip, goto originalCode
            target.visitLabel(notLastFrame);
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "advanceFrame", "()V", false);

            if (info.opcode == Opcodes.INVOKEDYNAMIC) {
                target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "ai/jacc/durableThreads/ReplayState", "resumePoint", "()V", false);
            } else {
                pushDummyArguments(info);
                target.visitMethodInsn(info.opcode, info.owner, info.name,
                        info.descriptor, info.isInterface);
                // Box the return value and save it for the skip path
                boxReturnValue(Type.getReturnType(info.descriptor));
                target.visitVarInsn(Opcodes.ASTORE, retValSlot);
            }

            emitIntConst(invokeIndex);
            target.visitVarInsn(Opcodes.ISTORE, skipSlot);
            target.visitJumpInsn(Opcodes.GOTO, originalCode);
        }

        /**
         * Emit original code with skip checks inserted right before each invoke.
         *
         * <p>Key change: the skip check uses {@code __skip >= index} instead of
         * {@code __skip == index}. This skips ALL invokes from index 0 through
         * the target invoke, preventing re-execution of side effects during replay.</p>
         *
         * <p>When the target invoke itself is reached ({@code __skip == index}),
         * the skip check also calls {@code localsReady()} to block until the JDI
         * worker sets local variables. At this point, all locals are in scope.</p>
         */
        private void emitOriginalCodeWithSkipChecks(int skipSlot, int retValSlot) {
            for (Runnable op : bufferedOps) {
                if (op instanceof InvokeMarker marker) {
                    emitInvokeWithSkipCheck(skipSlot, retValSlot, marker.index, marker.opcode,
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
         * Emit skip check + invoke + postInvoke label.
         *
         * <pre>
         *   if (__skip &lt; index) goto doInvoke       // skip if __skip &gt;= index
         *   pop receiver+args
         *   if (__skip != index) goto afterSync       // only sync at target invoke
         *   __skip = -1
         *   localsReady()                              // block for JDI to set locals
         *   unbox __retVal → push actual return        // preserve return value from resume stub
         *   goto postInvoke
         * afterSync:
         *   push dummy return                          // non-target skipped invoke: dummy is fine
         *   goto postInvoke
         * doInvoke:
         *   invoke
         * postInvoke:
         * </pre>
         */
        private void emitInvokeWithSkipCheck(int skipSlot, int retValSlot, int index,
                                              int opcode, String owner, String name,
                                              String desc, boolean isInterface) {
            Label postInvoke = new Label();
            Label doInvoke = new Label();
            Label afterSync = new Label();

            // Skip check: if __skip >= index, skip this invoke
            target.visitVarInsn(Opcodes.ILOAD, skipSlot);
            emitIntConst(index);
            target.visitJumpInsn(Opcodes.IF_ICMPLT, doInvoke); // __skip < index → don't skip

            // SKIP PATH: pop args from stack
            popInvokeArgs(opcode, desc);

            // Check if this is the target invoke (__skip == index)
            target.visitVarInsn(Opcodes.ILOAD, skipSlot);
            emitIntConst(index);
            target.visitJumpInsn(Opcodes.IF_ICMPNE, afterSync); // __skip != index → skip sync

            // TARGET INVOKE: reset __skip and block for JDI to set locals
            target.visitInsn(Opcodes.ICONST_M1);
            target.visitVarInsn(Opcodes.ISTORE, skipSlot);
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "localsReady", "()V", false);

            // Unbox the return value saved by the resume stub
            unboxReturnValue(Type.getReturnType(desc), retValSlot);
            target.visitJumpInsn(Opcodes.GOTO, postInvoke);

            target.visitLabel(afterSync);
            // Non-target skipped invoke: dummy return is fine (locals overwritten by JDI later)
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
            Label afterSync = new Label();

            // Skip check: if __skip >= index, skip this invoke
            target.visitVarInsn(Opcodes.ILOAD, skipSlot);
            emitIntConst(index);
            target.visitJumpInsn(Opcodes.IF_ICMPLT, doInvoke);

            // SKIP PATH
            popInvokeArgs(Opcodes.INVOKESTATIC, desc); // invokedynamic has no receiver

            // Check if this is the target invoke
            target.visitVarInsn(Opcodes.ILOAD, skipSlot);
            emitIntConst(index);
            target.visitJumpInsn(Opcodes.IF_ICMPNE, afterSync);

            // TARGET: reset __skip and block for locals
            target.visitInsn(Opcodes.ICONST_M1);
            target.visitVarInsn(Opcodes.ISTORE, skipSlot);
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "localsReady", "()V", false);

            target.visitLabel(afterSync);
            pushDummyReturnValue(desc);
            target.visitJumpInsn(Opcodes.GOTO, postInvoke);

            // NORMAL PATH
            target.visitLabel(doInvoke);
            target.visitInvokeDynamicInsn(name, desc, bsmHandle, bsmArgs);

            target.visitLabel(postInvoke);
        }

        /**
         * Pop the receiver (for non-static) and all arguments from the stack.
         */
        private void popInvokeArgs(int opcode, String desc) {
            Type[] argTypes = Type.getArgumentTypes(desc);

            // Pop arguments (rightmost first, they're on top of stack)
            for (int i = argTypes.length - 1; i >= 0; i--) {
                popValue(argTypes[i]);
            }

            // Pop receiver for non-static invokes
            if (opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKEDYNAMIC) {
                target.visitInsn(Opcodes.POP);
            }
        }

        // --- Helpers ---

        private void pushDummyArguments(InvokeInfo info) {
            if (info.opcode != Opcodes.INVOKESTATIC && info.opcode != Opcodes.INVOKEDYNAMIC) {
                target.visitLdcInsn(info.owner.replace('/', '.'));
                target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "ai/jacc/durableThreads/ReplayState", "dummyInstance",
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

        private static final String RS = "ai/jacc/durableThreads/ReplayState";

        /**
         * Box the return value on top of the operand stack into an Object.
         * For void returns, pushes null.
         *
         * <p>All calls go through ReplayState helpers so that RawBytecodeScanner
         * filters them out (it excludes ReplayState invokes). Using direct
         * java.lang.Integer.valueOf() etc. would corrupt the invoke index mapping.</p>
         */
        private void boxReturnValue(Type retType) {
            switch (retType.getSort()) {
                case Type.VOID -> target.visitInsn(Opcodes.ACONST_NULL);
                case Type.BOOLEAN -> target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        RS, "boxBoolean", "(Z)Ljava/lang/Object;", false);
                case Type.BYTE -> target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        RS, "boxByte", "(B)Ljava/lang/Object;", false);
                case Type.CHAR -> target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        RS, "boxChar", "(C)Ljava/lang/Object;", false);
                case Type.SHORT -> target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        RS, "boxShort", "(S)Ljava/lang/Object;", false);
                case Type.INT -> target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        RS, "boxInt", "(I)Ljava/lang/Object;", false);
                case Type.LONG -> target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        RS, "boxLong", "(J)Ljava/lang/Object;", false);
                case Type.FLOAT -> target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        RS, "boxFloat", "(F)Ljava/lang/Object;", false);
                case Type.DOUBLE -> target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        RS, "boxDouble", "(D)Ljava/lang/Object;", false);
                default -> {} // OBJECT and ARRAY are already references
            }
        }

        /**
         * Load the boxed return value from retValSlot and unbox it to the expected type.
         * For void returns, does nothing (the boxed null is ignored).
         *
         * <p>All calls go through ReplayState helpers so that RawBytecodeScanner
         * filters them out.</p>
         */
        private void unboxReturnValue(Type retType, int retValSlot) {
            switch (retType.getSort()) {
                case Type.VOID -> {} // nothing to push
                case Type.BOOLEAN -> {
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxBoolean", "(Ljava/lang/Object;)Z", false);
                }
                case Type.BYTE -> {
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxByte", "(Ljava/lang/Object;)B", false);
                }
                case Type.CHAR -> {
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxChar", "(Ljava/lang/Object;)C", false);
                }
                case Type.SHORT -> {
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxShort", "(Ljava/lang/Object;)S", false);
                }
                case Type.INT -> {
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxInt", "(Ljava/lang/Object;)I", false);
                }
                case Type.LONG -> {
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxLong", "(Ljava/lang/Object;)J", false);
                }
                case Type.FLOAT -> {
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxFloat", "(Ljava/lang/Object;)F", false);
                }
                case Type.DOUBLE -> {
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxDouble", "(Ljava/lang/Object;)D", false);
                }
                default -> {
                    // OBJECT or ARRAY — load and cast to expected type
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitTypeInsn(Opcodes.CHECKCAST, retType.getInternalName());
                }
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

    record LocalVarInfo(String name, String desc, String sig,
                        Label start, Label end, int index) {}
}
