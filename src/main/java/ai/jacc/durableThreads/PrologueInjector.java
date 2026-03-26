package ai.jacc.durableThreads;

import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * ASM-based bytecode transformer that injects the universal replay prologue.
 *
 * <h3>Architecture (Direct-Jump)</h3>
 * <pre>
 * METHOD ENTRY:
 *   __retVal = null
 *   if (!isReplayThread()) goto ORIGINAL_CODE
 *   switch (currentResumeIndex()):
 *     case N: goto RESUME_N
 *
 * RESUME_N:
 *   if (isLastFrame) { resumePoint(); } else { advanceFrame(); invoke next; }
 *   initLocalDefaults()                  // init non-param locals for verifier
 *   localsReady()                        // block for JDI to set actual locals
 *   [push return value if non-void]
 *   goto POST_INVOKE_N                   // jump directly past the invoke
 *
 * ORIGINAL_CODE:
 *   ... original bytecode ...
 *   invoke 0
 *   POST_INVOKE_0:
 *   ...
 *   invoke 1
 *   POST_INVOKE_1:
 *   ...
 * </pre>
 *
 * <p>During replay, the resume stub jumps directly to the post-invoke label
 * for the target invoke. No original code is re-executed, so there are no
 * side effects from println, I/O, field writes, etc. The JDI worker sets
 * all local variables at {@code localsReady()}, then execution continues
 * past the freeze point with correct locals.</p>
 */
public final class PrologueInjector extends ClassVisitor {

    private String className;
    /** method key ("name+desc") → number of original invokes that received indices. */
    private final java.util.Map<String, Integer> invokeCountsByMethod = new java.util.HashMap<>();
    /** method key → set of invoke indices that are invokedynamic (no stub re-invoke). */
    private final java.util.Map<String, java.util.Set<Integer>> indyIndicesByMethod = new java.util.HashMap<>();

    public PrologueInjector(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    /**
     * Returns the number of original invokes that PrologueInjector assigned
     * indices to in the given method.  This is the authoritative count —
     * the raw bytecode scanner may see additional invokes in resume stubs,
     * but only the last {@code getOriginalInvokeCount()} entries in
     * the scanner's list correspond to the original code.
     */
    public int getOriginalInvokeCount(String methodName, String methodDesc) {
        Integer n = invokeCountsByMethod.get(methodName + methodDesc);
        return n != null ? n : 0;
    }

    /**
     * Returns the set of invoke indices that are invokedynamic in the given method.
     * These stubs have no user re-invoke, so the raw scanner won't find them.
     */
    public java.util.Set<Integer> getInvokeDynamicIndices(String methodName, String methodDesc) {
        java.util.Set<Integer> s = indyIndicesByMethod.get(methodName + methodDesc);
        return s != null ? s : java.util.Collections.emptySet();
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
                invokeCountsByMethod, indyIndicesByMethod);
    }

    private static class PrologueMethodVisitor extends MethodVisitor {

        private final int methodAccess;
        private final String methodName;
        private final String methodDesc;
        private final String className;
        private final MethodVisitor target;
        private final java.util.Map<String, Integer> invokeCountsOut;
        private final java.util.Map<String, java.util.Set<Integer>> indyIndicesOut;

        private final List<InvokeInfo> invokeInfos = new ArrayList<>();
        private final List<Runnable> bufferedOps = new ArrayList<>();
        private final List<LocalVarInfo> localVars = new ArrayList<>();

        private int invokeCounter = 0;
        private boolean hasCode = false;
        private int originalMaxLocals = 0;

        // Method-wide labels for extending local variable scope.
        private Label methodStartLabel;
        private Label methodEndLabel;

        // --- Operand stack simulator ---
        // Tracks the type category of each stack entry during buffering so
        // resume stubs can reconstruct the sub-stack (items below the invoke's
        // args) at each post-invoke label for verifier-compatible merges.
        private final List<Character> simStack = new ArrayList<>();
        private boolean simLost = false; // true after GOTO/RETURN/ATHROW
        private final java.util.Map<Integer, List<Character>> invokeSubStacks = new java.util.HashMap<>();
        // Saved stack states at branch target labels for recovery after GOTO/RETURN
        private final java.util.Map<Label, List<Character>> labelStacks = new java.util.IdentityHashMap<>();

        private void simPush(char cat) { if (!simLost) simStack.add(cat); }
        private char simPop() {
            if (simLost || simStack.isEmpty()) return '?';
            return simStack.remove(simStack.size() - 1);
        }
        private char simPeek() {
            if (simLost || simStack.isEmpty()) return '?';
            return simStack.get(simStack.size() - 1);
        }
        private void simMarkLost() { simStack.clear(); simLost = true; }
        private void simSaveLabel(Label label) {
            if (!simLost) labelStacks.putIfAbsent(label, new ArrayList<>(simStack));
        }
        private static boolean isWide(char c) { return c == 'J' || c == 'D'; }

        PrologueMethodVisitor(int access, String name, String desc, String className,
                              MethodVisitor target,
                              java.util.Map<String, Integer> invokeCountsOut,
                              java.util.Map<String, java.util.Set<Integer>> indyIndicesOut) {
            super(Opcodes.ASM9, null);
            this.methodAccess = access;
            this.methodName = name;
            this.methodDesc = desc;
            this.className = className;
            this.target = target;
            this.invokeCountsOut = invokeCountsOut;
            this.indyIndicesOut = indyIndicesOut;
        }

        // --- Buffering ---

        @Override public void visitCode() { hasCode = true; }
        @Override public void visitInsn(int opcode) {
            if (!simLost) simulateInsn(opcode);
            bufferedOps.add(() -> target.visitInsn(opcode));
        }

        private void simulateInsn(int opcode) {
            switch (opcode) {
                case Opcodes.ACONST_NULL: simPush('A'); break;
                case Opcodes.ICONST_M1: case Opcodes.ICONST_0: case Opcodes.ICONST_1:
                case Opcodes.ICONST_2: case Opcodes.ICONST_3: case Opcodes.ICONST_4:
                case Opcodes.ICONST_5: simPush('I'); break;
                case Opcodes.LCONST_0: case Opcodes.LCONST_1: simPush('J'); break;
                case Opcodes.FCONST_0: case Opcodes.FCONST_1: case Opcodes.FCONST_2: simPush('F'); break;
                case Opcodes.DCONST_0: case Opcodes.DCONST_1: simPush('D'); break;
                case Opcodes.IALOAD: case Opcodes.BALOAD: case Opcodes.CALOAD:
                case Opcodes.SALOAD: simPop(); simPop(); simPush('I'); break;
                case Opcodes.LALOAD: simPop(); simPop(); simPush('J'); break;
                case Opcodes.FALOAD: simPop(); simPop(); simPush('F'); break;
                case Opcodes.DALOAD: simPop(); simPop(); simPush('D'); break;
                case Opcodes.AALOAD: simPop(); simPop(); simPush('A'); break;
                case Opcodes.IASTORE: case Opcodes.BASTORE: case Opcodes.CASTORE:
                case Opcodes.SASTORE: case Opcodes.AASTORE: case Opcodes.FASTORE:
                    simPop(); simPop(); simPop(); break;
                case Opcodes.LASTORE: case Opcodes.DASTORE:
                    simPop(); simPop(); simPop(); break;
                case Opcodes.POP: simPop(); break;
                case Opcodes.POP2:
                    if (isWide(simPeek())) simPop(); else { simPop(); simPop(); } break;
                case Opcodes.DUP: { char t = simPeek(); simPush(t); break; }
                case Opcodes.DUP_X1: {
                    char v1 = simPop(); char v2 = simPop();
                    simPush(v1); simPush(v2); simPush(v1); break;
                }
                case Opcodes.DUP_X2: {
                    char v1 = simPop();
                    if (isWide(simPeek())) {
                        char v2 = simPop();
                        simPush(v1); simPush(v2); simPush(v1);
                    } else {
                        char v2 = simPop(); char v3 = simPop();
                        simPush(v1); simPush(v3); simPush(v2); simPush(v1);
                    }
                    break;
                }
                case Opcodes.DUP2: {
                    if (isWide(simPeek())) {
                        simPush(simPeek());
                    } else {
                        char v1 = simPop(); char v2 = simPeek();
                        simPush(v1); simPush(v2); simPush(v1);
                    }
                    break;
                }
                case Opcodes.DUP2_X1: {
                    if (isWide(simPeek())) {
                        char v1 = simPop(); char v2 = simPop();
                        simPush(v1); simPush(v2); simPush(v1);
                    } else {
                        char v1 = simPop(); char v2 = simPop(); char v3 = simPop();
                        simPush(v2); simPush(v1); simPush(v3); simPush(v2); simPush(v1);
                    }
                    break;
                }
                case Opcodes.DUP2_X2: simMarkLost(); break; // rare; punt
                case Opcodes.SWAP: {
                    char v1 = simPop(); char v2 = simPop();
                    simPush(v1); simPush(v2); break;
                }
                case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL: case Opcodes.IDIV:
                case Opcodes.IREM: case Opcodes.IAND: case Opcodes.IOR: case Opcodes.IXOR:
                case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR:
                    simPop(); simPop(); simPush('I'); break;
                case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL: case Opcodes.LDIV:
                case Opcodes.LREM: case Opcodes.LAND: case Opcodes.LOR: case Opcodes.LXOR:
                case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR:
                    simPop(); simPop(); simPush('J'); break;
                case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL: case Opcodes.FDIV:
                case Opcodes.FREM: simPop(); simPop(); simPush('F'); break;
                case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL: case Opcodes.DDIV:
                case Opcodes.DREM: simPop(); simPop(); simPush('D'); break;
                case Opcodes.INEG: case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S:
                    simPop(); simPush('I'); break;
                case Opcodes.LNEG: simPop(); simPush('J'); break;
                case Opcodes.FNEG: simPop(); simPush('F'); break;
                case Opcodes.DNEG: simPop(); simPush('D'); break;
                case Opcodes.I2L: simPop(); simPush('J'); break;
                case Opcodes.I2F: simPop(); simPush('F'); break;
                case Opcodes.I2D: simPop(); simPush('D'); break;
                case Opcodes.L2I: simPop(); simPush('I'); break;
                case Opcodes.L2F: simPop(); simPush('F'); break;
                case Opcodes.L2D: simPop(); simPush('D'); break;
                case Opcodes.F2I: simPop(); simPush('I'); break;
                case Opcodes.F2L: simPop(); simPush('J'); break;
                case Opcodes.F2D: simPop(); simPush('D'); break;
                case Opcodes.D2I: simPop(); simPush('I'); break;
                case Opcodes.D2L: simPop(); simPush('J'); break;
                case Opcodes.D2F: simPop(); simPush('F'); break;
                case Opcodes.LCMP: simPop(); simPop(); simPush('I'); break;
                case Opcodes.FCMPL: case Opcodes.FCMPG:
                    simPop(); simPop(); simPush('I'); break;
                case Opcodes.DCMPL: case Opcodes.DCMPG:
                    simPop(); simPop(); simPush('I'); break;
                case Opcodes.IRETURN: case Opcodes.LRETURN: case Opcodes.FRETURN:
                case Opcodes.DRETURN: case Opcodes.ARETURN: case Opcodes.RETURN:
                case Opcodes.ATHROW: simMarkLost(); break;
                case Opcodes.ARRAYLENGTH: simPop(); simPush('I'); break;
                case Opcodes.MONITORENTER: case Opcodes.MONITOREXIT: simPop(); break;
                default: break; // NOP etc.
            }
        }
        @Override public void visitIntInsn(int opcode, int operand) {
            if (!simLost) {
                switch (opcode) {
                    case Opcodes.BIPUSH: case Opcodes.SIPUSH: simPush('I'); break;
                    case Opcodes.NEWARRAY: simPop(); simPush('A'); break;
                }
            }
            bufferedOps.add(() -> target.visitIntInsn(opcode, operand));
        }
        @Override public void visitVarInsn(int opcode, int varIndex) {
            if (!simLost) {
                switch (opcode) {
                    case Opcodes.ILOAD: simPush('I'); break;
                    case Opcodes.LLOAD: simPush('J'); break;
                    case Opcodes.FLOAD: simPush('F'); break;
                    case Opcodes.DLOAD: simPush('D'); break;
                    case Opcodes.ALOAD: simPush('A'); break;
                    case Opcodes.ISTORE: case Opcodes.FSTORE: case Opcodes.ASTORE: simPop(); break;
                    case Opcodes.LSTORE: case Opcodes.DSTORE: simPop(); break;
                }
            }
            bufferedOps.add(() -> target.visitVarInsn(opcode, varIndex));
        }
        @Override public void visitTypeInsn(int opcode, String type) {
            if (!simLost) {
                switch (opcode) {
                    case Opcodes.NEW: simPush('A'); break;
                    case Opcodes.ANEWARRAY: simPop(); simPush('A'); break;
                    case Opcodes.CHECKCAST: /* pop A, push A — no net change */ break;
                    case Opcodes.INSTANCEOF: simPop(); simPush('I'); break;
                }
            }
            bufferedOps.add(() -> target.visitTypeInsn(opcode, type));
        }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            if (!simLost) {
                char fcat = typeCategory(Type.getType(desc));
                switch (opcode) {
                    case Opcodes.GETSTATIC: simPush(fcat); break;
                    case Opcodes.PUTSTATIC: simPop(); break;
                    case Opcodes.GETFIELD: simPop(); simPush(fcat); break;
                    case Opcodes.PUTFIELD: simPop(); simPop(); break;
                }
            }
            bufferedOps.add(() -> target.visitFieldInsn(opcode, owner, name, desc));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                                    boolean isInterface) {
            boolean isConstructorCall = (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name));

            // Stack simulation: compute sub-stack before popping args
            if (!simLost) {
                int argEntries = Type.getArgumentTypes(desc).length;
                if (opcode != Opcodes.INVOKESTATIC) argEntries++; // receiver

                if (!isConstructorCall) {
                    int subSize = Math.max(0, simStack.size() - argEntries);
                    invokeSubStacks.put(invokeCounter, new ArrayList<>(simStack.subList(0, subSize)));
                }

                for (int i = 0; i < argEntries; i++) simPop();
                Type retType = Type.getReturnType(desc);
                if (retType.getSort() != Type.VOID) simPush(typeCategory(retType));
            }

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
            if (!simLost) {
                int argEntries = Type.getArgumentTypes(desc).length;
                int subSize = Math.max(0, simStack.size() - argEntries);
                invokeSubStacks.put(invokeCounter, new ArrayList<>(simStack.subList(0, subSize)));
                for (int i = 0; i < argEntries; i++) simPop();
                Type retType = Type.getReturnType(desc);
                if (retType.getSort() != Type.VOID) simPush(typeCategory(retType));
            }
            int idx = invokeCounter++;
            invokeInfos.add(new InvokeInfo(idx, Opcodes.INVOKEDYNAMIC, null, name, desc, false));
            bufferedOps.add(new InvokeDynamicMarker(idx, name, desc, bsmHandle, bsmArgs));
        }

        @Override public void visitJumpInsn(int opcode, Label label) {
            if (!simLost) {
                switch (opcode) {
                    case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT:
                    case Opcodes.IFGE: case Opcodes.IFGT: case Opcodes.IFLE:
                    case Opcodes.IFNULL: case Opcodes.IFNONNULL:
                        simPop();
                        simSaveLabel(label); // branch target sees post-pop stack
                        break;
                    case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE: case Opcodes.IF_ICMPLT:
                    case Opcodes.IF_ICMPGE: case Opcodes.IF_ICMPGT: case Opcodes.IF_ICMPLE:
                    case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE:
                        simPop(); simPop();
                        simSaveLabel(label);
                        break;
                    case Opcodes.GOTO:
                        simSaveLabel(label); // save stack before losing track
                        simMarkLost();
                        break;
                    case Opcodes.JSR: simPush('A'); break;
                }
            }
            bufferedOps.add(() -> target.visitJumpInsn(opcode, label));
        }
        @Override public void visitLabel(Label label) {
            if (simLost) {
                List<Character> saved = labelStacks.get(label);
                if (saved != null) {
                    simLost = false;
                    simStack.clear();
                    simStack.addAll(saved);
                } else {
                    simLost = false;
                    simStack.clear();
                }
            }
            bufferedOps.add(new LabelOp(label));
        }
        @Override public void visitLdcInsn(Object value) {
            if (!simLost) {
                if (value instanceof Integer) simPush('I');
                else if (value instanceof Long) simPush('J');
                else if (value instanceof Float) simPush('F');
                else if (value instanceof Double) simPush('D');
                else simPush('A'); // String, Type, Handle, ConstantDynamic
            }
            bufferedOps.add(() -> target.visitLdcInsn(value));
        }
        @Override public void visitIincInsn(int varIndex, int increment) {
            // No stack change
            bufferedOps.add(() -> target.visitIincInsn(varIndex, increment));
        }
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            if (!simLost) {
                simPop(); // consume int key
                simSaveLabel(dflt);
                for (Label l : labels) simSaveLabel(l);
            }
            simMarkLost(); // multiple branch targets
            bufferedOps.add(() -> target.visitTableSwitchInsn(min, max, dflt, labels));
        }
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            if (!simLost) {
                simPop();
                simSaveLabel(dflt);
                for (Label l : labels) simSaveLabel(l);
            }
            simMarkLost();
            bufferedOps.add(() -> target.visitLookupSwitchInsn(dflt, keys, labels));
        }
        @Override public void visitMultiANewArrayInsn(String desc, int numDimensions) {
            if (!simLost) {
                for (int i = 0; i < numDimensions; i++) simPop();
                simPush('A');
            }
            bufferedOps.add(() -> target.visitMultiANewArrayInsn(desc, numDimensions));
        }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            // Exception handler entry: stack = [exception_reference]
            List<Character> handlerStack = new ArrayList<>();
            handlerStack.add('A');
            labelStacks.putIfAbsent(handler, handlerStack);
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
            // of original-code invokes that received indices.
            invokeCountsOut.put(methodName + methodDesc, invokeCounter);

            // Record which invoke indices are invokedynamic (their stubs have
            // no user re-invoke, so the raw scanner won't find them).
            java.util.Set<Integer> indySet = new java.util.HashSet<>();
            for (InvokeInfo info : invokeInfos) {
                if (info.opcode == Opcodes.INVOKEDYNAMIC) {
                    indySet.add(info.index);
                }
            }
            if (!indySet.isEmpty()) {
                indyIndicesOut.put(methodName + methodDesc, indySet);
            }

            if (!hasCode) {
                target.visitEnd();
                return;
            }

            target.visitCode();

            if (invokeInfos.isEmpty()) {
                emitNoInvokePrologue();
            } else {
                emitFullPrologue();
            }

            // Emit deduplicated local variables with method-wide scope
            emitLocalVariables();

            target.visitMaxs(0, 0);
            target.visitEnd();
        }

        /**
         * Emit local variable debug info. Parameters get method-wide scope
         * so JDI can set them at any BCI. Non-parameter locals keep their
         * original scope — with the direct-jump architecture, all frames are
         * in their original code sections where the compiler's scopes apply.
         */
        private void emitLocalVariables() {
            if (methodStartLabel == null || methodEndLabel == null) {
                for (LocalVarInfo lv : localVars) {
                    target.visitLocalVariable(lv.name(), lv.desc(), lv.sig(),
                            lv.start(), lv.end(), lv.index());
                }
                return;
            }

            int paramSlots = 0;
            if ((methodAccess & Opcodes.ACC_STATIC) == 0) paramSlots = 1;
            for (Type t : Type.getArgumentTypes(methodDesc)) paramSlots += t.getSize();

            java.util.Set<String> seenParams = new java.util.HashSet<>();
            for (LocalVarInfo lv : localVars) {
                if (lv.index() < paramSlots) {
                    String key = lv.name() + "\0" + lv.desc() + "\0" + lv.index();
                    if (seenParams.add(key)) {
                        target.visitLocalVariable(lv.name(), lv.desc(), lv.sig(),
                                methodStartLabel, methodEndLabel, lv.index());
                    }
                } else {
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

        private void emitFullPrologue() {
            // Build per-invoke scope maps before emitting stubs so each stub
            // initializes locals with types matching the target invoke label.
            buildPerInvokeScopeMaps();

            Label originalCode = new Label();

            // Pre-create labels for jump targets in original code
            Label[] beforeInvokeLabels = new Label[invokeInfos.size()];
            Label[] postInvokeLabels = new Label[invokeInfos.size()];
            for (int i = 0; i < invokeInfos.size(); i++) {
                beforeInvokeLabels[i] = new Label();
                postInvokeLabels[i] = new Label();
            }

            // Method-wide labels for extending local variable scope
            methodStartLabel = new Label();
            methodEndLabel = new Label();
            target.visitLabel(methodStartLabel);

            // --- PROLOGUE ---

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
                emitResumeStub(i, invokeInfos.get(i),
                        beforeInvokeLabels[i], postInvokeLabels[i]);
            }

            // --- ORIGINAL CODE with before/post-invoke labels ---
            target.visitLabel(originalCode);
            emitOriginalCode(beforeInvokeLabels, postInvokeLabels);

            // End label for method-wide local variable scope
            target.visitLabel(methodEndLabel);
        }

        /**
         * Resume stub: direct-jump-to-original-code approach.
         *
         * <p>For the deepest frame: init local defaults (for verifier), call
         * {@code resumePoint()} which blocks while JDI sets ALL locals in ALL
         * frames in a single pass (all frames are in original code sections),
         * deactivate replay, push sub-stack defaults + dummy return value,
         * jump to POST_INVOKE (past the freeze call).</p>
         *
        /**
         * Resume stub: single-pass restore via direct jump to original code.
         *
         * <p>Both deepest and non-deepest frames jump to BEFORE_INVOKE in the
         * original code, letting the original invoke instruction make the call.
         * This puts every frame in its original code section with all locals
         * in scope.</p>
         *
         * <p>For the deepest frame, the original invoke is the call to
         * {@code freeze()}. {@code freeze()} detects the restore context and
         * blocks on the go-latch instead of actually freezing. When the latch
         * is released, {@code freeze()} returns normally and user code
         * continues.</p>
         *
         * <p>For non-deepest frames, the original invoke calls the deeper
         * method, whose prologue takes over and dispatches to its own stub.</p>
         */
        private void emitResumeStub(int invokeIndex, InvokeInfo info,
                                    Label beforeInvokeLabel, Label postInvokeLabel) {
            List<Character> subStack = invokeSubStacks.getOrDefault(invokeIndex,
                    java.util.Collections.emptyList());
            Label notLastFrame = new Label();

            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "isLastFrame", "()Z", false);
            target.visitJumpInsn(Opcodes.IFEQ, notLastFrame);

            // === Deepest frame ===
            // Same as non-deepest: init defaults, jump to BEFORE_INVOKE.
            // The original invoke (freeze()) detects restore context and blocks.
            // Deactivate replay BEFORE the jump so freeze() doesn't re-enter
            // the replay prologue.
            emitLocalDefaults(invokeIndex);
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "deactivate", "()V", false);
            if (info.opcode == Opcodes.INVOKEDYNAMIC) {
                pushSubStackDefaults(subStack);
                pushDummyReturnValue(info.descriptor);
                target.visitJumpInsn(Opcodes.GOTO, postInvokeLabel);
            } else {
                pushSubStackDefaults(subStack);
                pushDummyArguments(info);
                target.visitJumpInsn(Opcodes.GOTO, beforeInvokeLabel);
            }

            // === Non-deepest frame ===
            target.visitLabel(notLastFrame);
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "ai/jacc/durableThreads/ReplayState", "advanceFrame", "()V", false);

            emitLocalDefaults(invokeIndex);

            if (info.opcode == Opcodes.INVOKEDYNAMIC) {
                pushSubStackDefaults(subStack);
                pushDummyReturnValue(info.descriptor);
                target.visitJumpInsn(Opcodes.GOTO, postInvokeLabel);
            } else {
                pushSubStackDefaults(subStack);
                pushDummyArguments(info);
                target.visitJumpInsn(Opcodes.GOTO, beforeInvokeLabel);
            }
        }

        /**
         * Push type-appropriate default values to reconstruct the sub-stack
         * (items that were on the operand stack below the invoke's arguments
         * on the normal execution path). This ensures the stack height and
         * types match at the post-invoke merge point.
         */
        private void pushSubStackDefaults(List<Character> subStack) {
            for (char cat : subStack) {
                switch (cat) {
                    case 'I': target.visitInsn(Opcodes.ICONST_0); break;
                    case 'J': target.visitInsn(Opcodes.LCONST_0); break;
                    case 'F': target.visitInsn(Opcodes.FCONST_0); break;
                    case 'D': target.visitInsn(Opcodes.DCONST_0); break;
                    case 'A': target.visitInsn(Opcodes.ACONST_NULL); break;
                }
            }
        }

        /**
         * Emit original code with before-invoke and post-invoke labels.
         *
         * <p>Before-invoke labels are jump targets for non-deepest resume stubs
         * (they jump here so the original invoke instruction makes the call,
         * keeping the frame in its original code section with locals in scope).
         * Post-invoke labels are jump targets for the deepest resume stub
         * (it jumps past the freeze call to continue user code).</p>
         */
        private void emitOriginalCode(Label[] beforeInvokeLabels,
                                      Label[] postInvokeLabels) {
            for (Runnable op : bufferedOps) {
                if (op instanceof InvokeMarker) {
                    InvokeMarker marker = (InvokeMarker) op;
                    target.visitLabel(beforeInvokeLabels[marker.index]);
                    target.visitMethodInsn(marker.opcode, marker.owner, marker.name,
                            marker.descriptor, marker.isInterface);
                    target.visitLabel(postInvokeLabels[marker.index]);
                } else if (op instanceof InvokeDynamicMarker) {
                    InvokeDynamicMarker marker = (InvokeDynamicMarker) op;
                    target.visitLabel(beforeInvokeLabels[marker.index]);
                    target.visitInvokeDynamicInsn(marker.name, marker.descriptor,
                            marker.bootstrapMethodHandle, marker.bootstrapMethodArguments);
                    target.visitLabel(postInvokeLabels[marker.index]);
                } else {
                    op.run();
                }
            }
        }

        /**
         * Per-invoke type maps: for each invoke index, the correct type category
         * for each local variable slot at that invoke's position in the code.
         * Built once by {@link #buildPerInvokeScopeMaps()} before stubs are emitted.
         */
        private java.util.Map<Integer, java.util.Map<Integer, Character>> perInvokeScopeMaps;

        /**
         * Build the per-invoke scope maps by walking through the buffered ops and
         * tracking which local variables are in scope at each invoke position.
         * This handles slot reuse (e.g., loop variable 'i' then StringBuilder 'sb'
         * at the same slot) by using the LAST variable assigned to each slot
         * before each invoke.
         */
        private void buildPerInvokeScopeMaps() {
            int paramSlots = 0;
            if ((methodAccess & Opcodes.ACC_STATIC) == 0) paramSlots = 1;
            for (Type t : Type.getArgumentTypes(methodDesc)) paramSlots += t.getSize();

            perInvokeScopeMaps = new java.util.HashMap<>();

            // Track which labels have been visited as we walk through buffered ops
            java.util.Set<Label> visitedLabels = new java.util.HashSet<>();
            for (Runnable op : bufferedOps) {
                if (op instanceof LabelOp) {
                    visitedLabels.add(((LabelOp) op).label);
                } else if (op instanceof InvokeMarker || op instanceof InvokeDynamicMarker) {
                    int idx = (op instanceof InvokeMarker)
                            ? ((InvokeMarker) op).index
                            : ((InvokeDynamicMarker) op).index;

                    // Determine slot types at this invoke position
                    java.util.Map<Integer, Character> slotTypes = new java.util.TreeMap<>();
                    for (LocalVarInfo lv : localVars) {
                        if (lv.index() >= paramSlots
                                && visitedLabels.contains(lv.start())
                                && !visitedLabels.contains(lv.end())) {
                            // Variable is in scope: start visited, end not yet
                            slotTypes.put(lv.index(), typeCategory(Type.getType(lv.desc())));
                        }
                    }
                    // Fill remaining slots with reference default
                    for (int i = paramSlots; i < originalMaxLocals; i++) {
                        slotTypes.putIfAbsent(i, 'A');
                    }
                    perInvokeScopeMaps.put(idx, slotTypes);
                }
            }
        }

        /**
         * Initialize non-parameter local slots to type-appropriate defaults
         * for a specific invoke target. The types are determined by which
         * variables are in scope at the invoke's position in the original code.
         */
        private void emitLocalDefaults(int invokeIndex) {
            java.util.Map<Integer, Character> slotCategories =
                    perInvokeScopeMaps != null ? perInvokeScopeMaps.get(invokeIndex) : null;

            if (slotCategories == null) {
                // Fallback: use all locals
                slotCategories = buildFallbackSlotCategories();
            }

            emitSlotDefaults(slotCategories);
        }

        private java.util.Map<Integer, Character> buildFallbackSlotCategories() {
            int paramSlots = 0;
            if ((methodAccess & Opcodes.ACC_STATIC) == 0) paramSlots = 1;
            for (Type t : Type.getArgumentTypes(methodDesc)) paramSlots += t.getSize();

            java.util.Map<Integer, Character> slotCategories = new java.util.TreeMap<>();
            for (LocalVarInfo lv : localVars) {
                if (lv.index() >= paramSlots) {
                    slotCategories.putIfAbsent(lv.index(), typeCategory(Type.getType(lv.desc())));
                }
            }
            for (int i = paramSlots; i < originalMaxLocals; i++) {
                slotCategories.putIfAbsent(i, 'A');
            }
            return slotCategories;
        }

        private void emitSlotDefaults(java.util.Map<Integer, Character> slotCategories) {
            // Skip wide-type second slots
            java.util.Set<Integer> wideSeconds = new java.util.HashSet<>();
            for (java.util.Map.Entry<Integer, Character> entry : slotCategories.entrySet()) {
                if (isWide(entry.getValue())) {
                    wideSeconds.add(entry.getKey() + 1);
                }
            }

            for (java.util.Map.Entry<Integer, Character> entry : slotCategories.entrySet()) {
                if (wideSeconds.contains(entry.getKey())) continue;
                int s = entry.getKey();
                char cat = entry.getValue();
                switch (cat) {
                    case 'I':
                        target.visitInsn(Opcodes.ICONST_0);
                        target.visitVarInsn(Opcodes.ISTORE, s);
                        break;
                    case 'J':
                        target.visitInsn(Opcodes.LCONST_0);
                        target.visitVarInsn(Opcodes.LSTORE, s);
                        break;
                    case 'F':
                        target.visitInsn(Opcodes.FCONST_0);
                        target.visitVarInsn(Opcodes.FSTORE, s);
                        break;
                    case 'D':
                        target.visitInsn(Opcodes.DCONST_0);
                        target.visitVarInsn(Opcodes.DSTORE, s);
                        break;
                    case 'A':
                        target.visitInsn(Opcodes.ACONST_NULL);
                        target.visitVarInsn(Opcodes.ASTORE, s);
                        break;
                }
            }
        }

        private static char typeCategory(Type t) {
            switch (t.getSort()) {
                case Type.BOOLEAN: case Type.BYTE: case Type.CHAR:
                case Type.SHORT: case Type.INT:
                    return 'I';
                case Type.LONG: return 'J';
                case Type.FLOAT: return 'F';
                case Type.DOUBLE: return 'D';
                default: return 'A';
            }
        }

        // --- Helpers ---

        private void pushDummyArguments(InvokeInfo info) {
            if (info.opcode != Opcodes.INVOKESTATIC && info.opcode != Opcodes.INVOKEDYNAMIC) {
                // Use resolveReceiver to get the pre-stored heap-restored receiver
                // for this frame, falling back to dummyInstance if unavailable.
                target.visitLdcInsn(info.owner.replace('/', '.'));
                target.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "ai/jacc/durableThreads/ReplayState", "resolveReceiver",
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
                case Type.VOID:
                    target.visitInsn(Opcodes.ACONST_NULL);
                    break;
                case Type.BOOLEAN:
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "boxBoolean", "(Z)Ljava/lang/Object;", false);
                    break;
                case Type.BYTE:
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "boxByte", "(B)Ljava/lang/Object;", false);
                    break;
                case Type.CHAR:
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "boxChar", "(C)Ljava/lang/Object;", false);
                    break;
                case Type.SHORT:
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "boxShort", "(S)Ljava/lang/Object;", false);
                    break;
                case Type.INT:
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "boxInt", "(I)Ljava/lang/Object;", false);
                    break;
                case Type.LONG:
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "boxLong", "(J)Ljava/lang/Object;", false);
                    break;
                case Type.FLOAT:
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "boxFloat", "(F)Ljava/lang/Object;", false);
                    break;
                case Type.DOUBLE:
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "boxDouble", "(D)Ljava/lang/Object;", false);
                    break;
                default:
                    // OBJECT and ARRAY are already references
                    break;
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
                case Type.VOID:
                    // nothing to push
                    break;
                case Type.BOOLEAN:
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxBoolean", "(Ljava/lang/Object;)Z", false);
                    break;
                case Type.BYTE:
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxByte", "(Ljava/lang/Object;)B", false);
                    break;
                case Type.CHAR:
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxChar", "(Ljava/lang/Object;)C", false);
                    break;
                case Type.SHORT:
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxShort", "(Ljava/lang/Object;)S", false);
                    break;
                case Type.INT:
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxInt", "(Ljava/lang/Object;)I", false);
                    break;
                case Type.LONG:
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxLong", "(Ljava/lang/Object;)J", false);
                    break;
                case Type.FLOAT:
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxFloat", "(Ljava/lang/Object;)F", false);
                    break;
                case Type.DOUBLE:
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC,
                            RS, "unboxDouble", "(Ljava/lang/Object;)D", false);
                    break;
                default:
                    // OBJECT or ARRAY — load and cast to expected type
                    target.visitVarInsn(Opcodes.ALOAD, retValSlot);
                    target.visitTypeInsn(Opcodes.CHECKCAST, retType.getInternalName());
                    break;
            }
        }

        private void pushDefaultValue(Type type) {
            switch (type.getSort()) {
                case Type.BOOLEAN:
                case Type.BYTE:
                case Type.CHAR:
                case Type.SHORT:
                case Type.INT:
                    target.visitInsn(Opcodes.ICONST_0);
                    break;
                case Type.LONG:
                    target.visitInsn(Opcodes.LCONST_0);
                    break;
                case Type.FLOAT:
                    target.visitInsn(Opcodes.FCONST_0);
                    break;
                case Type.DOUBLE:
                    target.visitInsn(Opcodes.DCONST_0);
                    break;
                case Type.ARRAY:
                case Type.OBJECT:
                    target.visitInsn(Opcodes.ACONST_NULL);
                    break;
                default:
                    break;
            }
        }

        // --- Marker types ---

        final class LabelOp implements Runnable {
            final Label label;
            LabelOp(Label label) { this.label = label; }
            @Override public void run() { target.visitLabel(label); }
        }

        static final class InvokeMarker implements Runnable {
            private final int index;
            private final int opcode;
            private final String owner;
            private final String name;
            private final String descriptor;
            private final boolean isInterface;

            InvokeMarker(int index, int opcode, String owner, String name,
                         String descriptor, boolean isInterface) {
                this.index = index;
                this.opcode = opcode;
                this.owner = owner;
                this.name = name;
                this.descriptor = descriptor;
                this.isInterface = isInterface;
            }

            public int index() { return index; }
            public int opcode() { return opcode; }
            public String owner() { return owner; }
            public String name() { return name; }
            public String descriptor() { return descriptor; }
            public boolean isInterface() { return isInterface; }

            @Override public void run() {
                throw new IllegalStateException("InvokeMarker must be handled");
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof InvokeMarker)) return false;
                InvokeMarker that = (InvokeMarker) o;
                return index == that.index && opcode == that.opcode
                        && isInterface == that.isInterface
                        && Objects.equals(owner, that.owner)
                        && Objects.equals(name, that.name)
                        && Objects.equals(descriptor, that.descriptor);
            }

            @Override
            public int hashCode() {
                return Objects.hash(index, opcode, owner, name, descriptor, isInterface);
            }

            @Override
            public String toString() {
                return "InvokeMarker[index=" + index + ", opcode=" + opcode
                        + ", owner=" + owner + ", name=" + name
                        + ", descriptor=" + descriptor + ", isInterface=" + isInterface + "]";
            }
        }

        static final class InvokeDynamicMarker implements Runnable {
            private final int index;
            private final String name;
            private final String descriptor;
            private final Handle bootstrapMethodHandle;
            private final Object[] bootstrapMethodArguments;

            InvokeDynamicMarker(int index, String name, String descriptor,
                                Handle bootstrapMethodHandle,
                                Object[] bootstrapMethodArguments) {
                this.index = index;
                this.name = name;
                this.descriptor = descriptor;
                this.bootstrapMethodHandle = bootstrapMethodHandle;
                this.bootstrapMethodArguments = bootstrapMethodArguments;
            }

            public int index() { return index; }
            public String name() { return name; }
            public String descriptor() { return descriptor; }
            public Handle bootstrapMethodHandle() { return bootstrapMethodHandle; }
            public Object[] bootstrapMethodArguments() { return bootstrapMethodArguments; }

            @Override public void run() {
                throw new IllegalStateException("InvokeDynamicMarker must be handled");
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof InvokeDynamicMarker)) return false;
                InvokeDynamicMarker that = (InvokeDynamicMarker) o;
                return index == that.index
                        && Objects.equals(name, that.name)
                        && Objects.equals(descriptor, that.descriptor)
                        && Objects.equals(bootstrapMethodHandle, that.bootstrapMethodHandle)
                        && Arrays.equals(bootstrapMethodArguments, that.bootstrapMethodArguments);
            }

            @Override
            public int hashCode() {
                int result = Objects.hash(index, name, descriptor, bootstrapMethodHandle);
                result = 31 * result + Arrays.hashCode(bootstrapMethodArguments);
                return result;
            }

            @Override
            public String toString() {
                return "InvokeDynamicMarker[index=" + index + ", name=" + name
                        + ", descriptor=" + descriptor
                        + ", bootstrapMethodHandle=" + bootstrapMethodHandle
                        + ", bootstrapMethodArguments=" + Arrays.toString(bootstrapMethodArguments) + "]";
            }
        }
    }

    static final class InvokeInfo {
        private final int index;
        private final int opcode;
        private final String owner;
        private final String name;
        private final String descriptor;
        private final boolean isInterface;

        InvokeInfo(int index, int opcode, String owner, String name, String descriptor,
                   boolean isInterface) {
            this.index = index;
            this.opcode = opcode;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.isInterface = isInterface;
        }

        public int index() { return index; }
        public int opcode() { return opcode; }
        public String owner() { return owner; }
        public String name() { return name; }
        public String descriptor() { return descriptor; }
        public boolean isInterface() { return isInterface; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InvokeInfo)) return false;
            InvokeInfo that = (InvokeInfo) o;
            return index == that.index && opcode == that.opcode
                    && isInterface == that.isInterface
                    && Objects.equals(owner, that.owner)
                    && Objects.equals(name, that.name)
                    && Objects.equals(descriptor, that.descriptor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public String toString() {
            return "InvokeInfo[index=" + index + ", opcode=" + opcode
                    + ", owner=" + owner + ", name=" + name
                    + ", descriptor=" + descriptor + ", isInterface=" + isInterface + "]";
        }
    }

    static final class LocalVarInfo {
        private final String name;
        private final String desc;
        private final String sig;
        private final Label start;
        private final Label end;
        private final int index;

        LocalVarInfo(String name, String desc, String sig,
                     Label start, Label end, int index) {
            this.name = name;
            this.desc = desc;
            this.sig = sig;
            this.start = start;
            this.end = end;
            this.index = index;
        }

        public String name() { return name; }
        public String desc() { return desc; }
        public String sig() { return sig; }
        public Label start() { return start; }
        public Label end() { return end; }
        public int index() { return index; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LocalVarInfo)) return false;
            LocalVarInfo that = (LocalVarInfo) o;
            return index == that.index
                    && Objects.equals(name, that.name)
                    && Objects.equals(desc, that.desc)
                    && Objects.equals(sig, that.sig)
                    && Objects.equals(start, that.start)
                    && Objects.equals(end, that.end);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, desc, sig, start, end, index);
        }

        @Override
        public String toString() {
            return "LocalVarInfo[name=" + name + ", desc=" + desc + ", sig=" + sig
                    + ", start=" + start + ", end=" + end + ", index=" + index + "]";
        }
    }
}
