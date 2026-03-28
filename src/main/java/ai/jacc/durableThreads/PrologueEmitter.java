package ai.jacc.durableThreads;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.*;

import ai.jacc.durableThreads.PrologueTypes.*;

/**
 * Emits the replay prologue, resume stubs, and original code with labels
 * into a target {@link MethodVisitor}.
 *
 * <p>This class receives the collected data from the buffering pass
 * (invoke infos, buffered ops, local vars, sub-stack maps) and produces
 * the final instrumented method bytecode.</p>
 */
final class PrologueEmitter {

    private final int methodAccess;
    private final String methodDesc;
    private final MethodVisitor target;
    private final List<InvokeInfo> invokeInfos;
    private final List<Runnable> bufferedOps;
    private final List<LocalVarInfo> localVars;
    private final OperandStackSimulator simulator;

    /** Per-invoke type maps built by {@link #buildPerInvokeScopeMaps()}. */
    private Map<Integer, Map<Integer, Character>> perInvokeScopeMaps;

    /** Method-wide labels for extending local variable scope. */
    private Label methodStartLabel;
    private Label methodEndLabel;

    PrologueEmitter(int methodAccess, String methodDesc, MethodVisitor target,
                    List<InvokeInfo> invokeInfos, List<Runnable> bufferedOps,
                    List<LocalVarInfo> localVars, OperandStackSimulator simulator) {
        this.methodAccess = methodAccess;
        this.methodDesc = methodDesc;
        this.target = target;
        this.invokeInfos = invokeInfos;
        this.bufferedOps = bufferedOps;
        this.localVars = localVars;
        this.simulator = simulator;
    }

    Label methodStartLabel() { return methodStartLabel; }
    Label methodEndLabel() { return methodEndLabel; }

    void emitNoInvokePrologue() {
        // Methods with zero invoke instructions can never be on the replay
        // call chain (every frame in the chain called a deeper method, which
        // IS an invoke). No replay prologue is needed — just emit the
        // original code with method-wide labels for local variable scoping.
        methodStartLabel = new Label();
        methodEndLabel = new Label();
        target.visitLabel(methodStartLabel);

        for (Runnable op : bufferedOps) {
            op.run();
        }

        target.visitLabel(methodEndLabel);
    }

    void emitFullPrologue() {
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
                    beforeInvokeLabels[i], postInvokeLabels[i], originalCode);
        }

        // --- ORIGINAL CODE with before/post-invoke labels ---
        target.visitLabel(originalCode);
        emitOriginalCode(beforeInvokeLabels, postInvokeLabels);

        // End label for method-wide local variable scope
        target.visitLabel(methodEndLabel);
    }

    /**
     * Emit local variable debug info with method-wide scope for parameters
     * only. Non-parameter locals keep their original scope ranges.
     */
    void emitLocalVariables() {
        if (methodStartLabel == null || methodEndLabel == null) {
            // No prologue was emitted — emit original entries unchanged
            for (LocalVarInfo lv : localVars) {
                target.visitLocalVariable(lv.name(), lv.desc(), lv.sig(),
                        lv.start(), lv.end(), lv.index());
            }
            return;
        }

        // Compute the number of parameter slots
        int paramSlots = 0;
        if ((methodAccess & Opcodes.ACC_STATIC) == 0) paramSlots = 1; // "this"
        for (Type t : Type.getArgumentTypes(methodDesc)) paramSlots += t.getSize();

        // Extend parameter scopes to method-wide; keep non-parameter scopes original.
        Set<String> paramsSeen = new HashSet<>();
        for (LocalVarInfo lv : localVars) {
            if (lv.index() < paramSlots) {
                // Parameter — extend to method-wide scope, deduplicate
                String key = lv.name() + "\0" + lv.desc() + "\0" + lv.index();
                if (paramsSeen.add(key)) {
                    target.visitLocalVariable(lv.name(), lv.desc(), lv.sig(),
                            methodStartLabel, methodEndLabel, lv.index());
                }
            } else {
                // Non-parameter — keep original scope, emit ALL entries
                target.visitLocalVariable(lv.name(), lv.desc(), lv.sig(),
                        lv.start(), lv.end(), lv.index());
            }
        }
    }

    // --- Resume stubs ---

    private void emitResumeStub(int invokeIndex, InvokeInfo info,
                                Label beforeInvokeLabel, Label postInvokeLabel,
                                Label originalCodeLabel) {
        List<Character> subStack = simulator.getSubStack(invokeIndex);

        if (subStack.contains('U')) {
            target.visitJumpInsn(Opcodes.GOTO, originalCodeLabel);
            return;
        }

        Label notLastFrame = new Label();

        target.visitMethodInsn(Opcodes.INVOKESTATIC,
                "ai/jacc/durableThreads/ReplayState", "isLastFrame", "()Z", false);
        target.visitJumpInsn(Opcodes.IFEQ, notLastFrame);

        // === Deepest frame ===
        emitLocalDefaults(invokeIndex);
        target.visitMethodInsn(Opcodes.INVOKESTATIC,
                "ai/jacc/durableThreads/ReplayState", "deactivate", "()V", false);

        pushSubStackDefaults(subStack);
        pushDummyArguments(info);
        target.visitJumpInsn(Opcodes.GOTO, beforeInvokeLabel);

        // === Non-deepest frame ===
        target.visitLabel(notLastFrame);
        target.visitMethodInsn(Opcodes.INVOKESTATIC,
                "ai/jacc/durableThreads/ReplayState", "advanceFrame", "()V", false);

        emitLocalDefaults(invokeIndex);

        pushSubStackDefaults(subStack);
        pushDummyArguments(info);
        target.visitJumpInsn(Opcodes.GOTO, beforeInvokeLabel);
    }

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

    // --- Local defaults ---

    private void buildPerInvokeScopeMaps() {
        int paramSlots = 0;
        if ((methodAccess & Opcodes.ACC_STATIC) == 0) paramSlots = 1;
        for (Type t : Type.getArgumentTypes(methodDesc)) paramSlots += t.getSize();

        perInvokeScopeMaps = new HashMap<>();

        Map<Integer, Character> lastStoreTypes = new TreeMap<>();
        Set<Label> visitedLabels = new HashSet<>();

        for (Runnable op : bufferedOps) {
            if (op instanceof LabelOp) {
                visitedLabels.add(((LabelOp) op).label);
            } else if (op instanceof StoreRecord) {
                StoreRecord sr = (StoreRecord) op;
                if (sr.slot >= paramSlots) {
                    lastStoreTypes.put(sr.slot, sr.typeCategory());
                }
            } else if (op instanceof InvokeMarker || op instanceof InvokeDynamicMarker) {
                int idx = (op instanceof InvokeMarker)
                        ? ((InvokeMarker) op).index
                        : ((InvokeDynamicMarker) op).index;

                Map<Integer, Character> slotTypes = new TreeMap<>(lastStoreTypes);

                for (LocalVarInfo lv : localVars) {
                    if (lv.index() >= paramSlots) {
                        if (visitedLabels.contains(lv.start())
                                && !visitedLabels.contains(lv.end())) {
                            slotTypes.put(lv.index(),
                                    OperandStackSimulator.typeCategory(Type.getType(lv.desc())));
                        }
                    }
                }

                perInvokeScopeMaps.put(idx, slotTypes);
            }
        }
    }

    private void emitLocalDefaults(int invokeIndex) {
        Map<Integer, Character> slotCategories =
                perInvokeScopeMaps != null ? perInvokeScopeMaps.get(invokeIndex) : null;

        if (slotCategories == null) {
            return;
        }

        // Skip wide-type second slots
        Set<Integer> wideSeconds = new HashSet<>();
        for (Map.Entry<Integer, Character> entry : slotCategories.entrySet()) {
            if (OperandStackSimulator.isWide(entry.getValue())) {
                wideSeconds.add(entry.getKey() + 1);
            }
        }

        for (Map.Entry<Integer, Character> entry : slotCategories.entrySet()) {
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

    // --- Helpers ---

    private void pushDummyArguments(InvokeInfo info) {
        if (info.opcode != Opcodes.INVOKESTATIC && info.opcode != Opcodes.INVOKEDYNAMIC) {
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
}
