package ai.jacc.durableThreads;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulates the JVM operand stack during bytecode buffering to track type
 * categories at each invoke instruction.
 *
 * <p>The simulator tracks what's on the stack at each invoke site so that
 * resume stubs can reconstruct the "sub-stack" (items below the invoke's
 * arguments) with type-appropriate default values. This ensures verifier-
 * compatible stack shapes at merge points.</p>
 *
 * <p>The simulation is best-effort: after unconditional jumps (GOTO, RETURN,
 * ATHROW), the stack state is "lost" until recovered at a label whose state
 * was saved before the jump. Unknown states produce '?' which resume stubs
 * handle conservatively.</p>
 */
final class OperandStackSimulator {

    private final List<Character> stack = new ArrayList<>();
    private boolean lost = false;

    /** Sub-stack state captured at each invoke index. */
    private final Map<Integer, List<Character>> invokeSubStacks = new java.util.HashMap<>();

    /** Saved stack states at branch target labels for recovery after GOTO/RETURN. */
    private final Map<Label, List<Character>> labelStacks = new IdentityHashMap<>();

    // 'U' represents an uninitialized reference from NEW (before <init>).
    // These can't be replicated with ACONST_NULL in sub-stack defaults.

    void push(char cat) { if (!lost) stack.add(cat); }

    char pop() {
        if (lost || stack.isEmpty()) return '?';
        return stack.remove(stack.size() - 1);
    }

    char peek() {
        if (lost || stack.isEmpty()) return '?';
        return stack.get(stack.size() - 1);
    }

    void markLost() { stack.clear(); lost = true; }

    void saveLabel(Label label) {
        if (!lost) labelStacks.putIfAbsent(label, new ArrayList<>(stack));
    }

    /**
     * Called when a label is visited. If the stack was lost (after GOTO/RETURN),
     * attempts to recover from a previously saved state for this label.
     */
    void visitLabel(Label label) {
        if (lost) {
            List<Character> saved = labelStacks.get(label);
            if (saved != null) {
                lost = false;
                stack.clear();
                stack.addAll(saved);
            } else {
                lost = false;
                stack.clear();
            }
        }
    }

    boolean isLost() { return lost; }

    static boolean isWide(char c) { return c == 'J' || c == 'D'; }

    /**
     * Record the sub-stack state for an invoke at the given index.
     * The sub-stack is everything below the invoke's arguments.
     *
     * @param invokeIndex the invoke's sequential index
     * @param argEntries  number of stack entries consumed by the invoke (receiver + args)
     */
    void captureSubStack(int invokeIndex, int argEntries) {
        if (!lost) {
            int subSize = Math.max(0, stack.size() - argEntries);
            invokeSubStacks.put(invokeIndex, new ArrayList<>(stack.subList(0, subSize)));
        }
    }

    /**
     * Get the sub-stack for an invoke index, or empty list if not captured.
     */
    List<Character> getSubStack(int invokeIndex) {
        return invokeSubStacks.getOrDefault(invokeIndex, Collections.emptyList());
    }

    // --- Instruction simulation ---

    void simulateInsn(int opcode) {
        if (lost) return;
        switch (opcode) {
            case Opcodes.ACONST_NULL: push('A'); break;
            case Opcodes.ICONST_M1: case Opcodes.ICONST_0: case Opcodes.ICONST_1:
            case Opcodes.ICONST_2: case Opcodes.ICONST_3: case Opcodes.ICONST_4:
            case Opcodes.ICONST_5: push('I'); break;
            case Opcodes.LCONST_0: case Opcodes.LCONST_1: push('J'); break;
            case Opcodes.FCONST_0: case Opcodes.FCONST_1: case Opcodes.FCONST_2: push('F'); break;
            case Opcodes.DCONST_0: case Opcodes.DCONST_1: push('D'); break;
            case Opcodes.IALOAD: case Opcodes.BALOAD: case Opcodes.CALOAD:
            case Opcodes.SALOAD: pop(); pop(); push('I'); break;
            case Opcodes.LALOAD: pop(); pop(); push('J'); break;
            case Opcodes.FALOAD: pop(); pop(); push('F'); break;
            case Opcodes.DALOAD: pop(); pop(); push('D'); break;
            case Opcodes.AALOAD: pop(); pop(); push('A'); break;
            case Opcodes.IASTORE: case Opcodes.BASTORE: case Opcodes.CASTORE:
            case Opcodes.SASTORE: case Opcodes.AASTORE: case Opcodes.FASTORE:
                pop(); pop(); pop(); break;
            case Opcodes.LASTORE: case Opcodes.DASTORE:
                pop(); pop(); pop(); break;
            case Opcodes.POP: pop(); break;
            case Opcodes.POP2:
                if (isWide(peek())) pop(); else { pop(); pop(); } break;
            case Opcodes.DUP: { char t = peek(); push(t); break; }
            case Opcodes.DUP_X1: {
                char v1 = pop(); char v2 = pop();
                push(v1); push(v2); push(v1); break;
            }
            case Opcodes.DUP_X2: {
                char v1 = pop();
                if (isWide(peek())) {
                    char v2 = pop();
                    push(v1); push(v2); push(v1);
                } else {
                    char v2 = pop(); char v3 = pop();
                    push(v1); push(v3); push(v2); push(v1);
                }
                break;
            }
            case Opcodes.DUP2: {
                if (isWide(peek())) {
                    push(peek());
                } else {
                    char v1 = pop(); char v2 = peek();
                    push(v1); push(v2); push(v1);
                }
                break;
            }
            case Opcodes.DUP2_X1: {
                if (isWide(peek())) {
                    char v1 = pop(); char v2 = pop();
                    push(v1); push(v2); push(v1);
                } else {
                    char v1 = pop(); char v2 = pop(); char v3 = pop();
                    push(v2); push(v1); push(v3); push(v2); push(v1);
                }
                break;
            }
            case Opcodes.DUP2_X2: markLost(); break; // rare; punt
            case Opcodes.SWAP: {
                char v1 = pop(); char v2 = pop();
                push(v1); push(v2); break;
            }
            case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL: case Opcodes.IDIV:
            case Opcodes.IREM: case Opcodes.IAND: case Opcodes.IOR: case Opcodes.IXOR:
            case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR:
                pop(); pop(); push('I'); break;
            case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL: case Opcodes.LDIV:
            case Opcodes.LREM: case Opcodes.LAND: case Opcodes.LOR: case Opcodes.LXOR:
            case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR:
                pop(); pop(); push('J'); break;
            case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL: case Opcodes.FDIV:
            case Opcodes.FREM: pop(); pop(); push('F'); break;
            case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL: case Opcodes.DDIV:
            case Opcodes.DREM: pop(); pop(); push('D'); break;
            case Opcodes.INEG: case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S:
                pop(); push('I'); break;
            case Opcodes.LNEG: pop(); push('J'); break;
            case Opcodes.FNEG: pop(); push('F'); break;
            case Opcodes.DNEG: pop(); push('D'); break;
            case Opcodes.I2L: pop(); push('J'); break;
            case Opcodes.I2F: pop(); push('F'); break;
            case Opcodes.I2D: pop(); push('D'); break;
            case Opcodes.L2I: pop(); push('I'); break;
            case Opcodes.L2F: pop(); push('F'); break;
            case Opcodes.L2D: pop(); push('D'); break;
            case Opcodes.F2I: pop(); push('I'); break;
            case Opcodes.F2L: pop(); push('J'); break;
            case Opcodes.F2D: pop(); push('D'); break;
            case Opcodes.D2I: pop(); push('I'); break;
            case Opcodes.D2L: pop(); push('J'); break;
            case Opcodes.D2F: pop(); push('F'); break;
            case Opcodes.LCMP: pop(); pop(); push('I'); break;
            case Opcodes.FCMPL: case Opcodes.FCMPG:
                pop(); pop(); push('I'); break;
            case Opcodes.DCMPL: case Opcodes.DCMPG:
                pop(); pop(); push('I'); break;
            case Opcodes.IRETURN: case Opcodes.LRETURN: case Opcodes.FRETURN:
            case Opcodes.DRETURN: case Opcodes.ARETURN: case Opcodes.RETURN:
            case Opcodes.ATHROW: markLost(); break;
            case Opcodes.ARRAYLENGTH: pop(); push('I'); break;
            case Opcodes.MONITORENTER: case Opcodes.MONITOREXIT: pop(); break;
            default: break; // NOP etc.
        }
    }

    void simulateIntInsn(int opcode) {
        if (lost) return;
        switch (opcode) {
            case Opcodes.BIPUSH: case Opcodes.SIPUSH: push('I'); break;
            case Opcodes.NEWARRAY: pop(); push('A'); break;
        }
    }

    void simulateVarInsn(int opcode) {
        if (lost) return;
        switch (opcode) {
            case Opcodes.ILOAD: push('I'); break;
            case Opcodes.LLOAD: push('J'); break;
            case Opcodes.FLOAD: push('F'); break;
            case Opcodes.DLOAD: push('D'); break;
            case Opcodes.ALOAD: push('A'); break;
            case Opcodes.ISTORE: case Opcodes.FSTORE: case Opcodes.ASTORE: pop(); break;
            case Opcodes.LSTORE: case Opcodes.DSTORE: pop(); break;
        }
    }

    void simulateTypeInsn(int opcode) {
        if (lost) return;
        switch (opcode) {
            case Opcodes.NEW: push('U'); break; // uninitialized ref
            case Opcodes.ANEWARRAY: pop(); push('A'); break;
            case Opcodes.CHECKCAST: /* pop A, push A — no net change */ break;
            case Opcodes.INSTANCEOF: pop(); push('I'); break;
        }
    }

    void simulateFieldInsn(int opcode, String desc) {
        if (lost) return;
        char fcat = typeCategory(Type.getType(desc));
        switch (opcode) {
            case Opcodes.GETSTATIC: push(fcat); break;
            case Opcodes.PUTSTATIC: pop(); break;
            case Opcodes.GETFIELD: pop(); push(fcat); break;
            case Opcodes.PUTFIELD: pop(); pop(); break;
        }
    }

    /**
     * Simulate a method invoke, capturing sub-stack before popping args.
     * @param invokeIndex sequential invoke index (or -1 for constructor calls)
     * @param opcode the invoke opcode
     * @param desc the method descriptor
     * @param isConstructor true for invokespecial &lt;init&gt;
     */
    void simulateMethodInsn(int invokeIndex, int opcode, String desc, boolean isConstructor) {
        if (lost) return;
        int argEntries = Type.getArgumentTypes(desc).length;
        if (opcode != Opcodes.INVOKESTATIC) argEntries++; // receiver

        if (!isConstructor) {
            captureSubStack(invokeIndex, argEntries);
        }

        for (int i = 0; i < argEntries; i++) pop();
        Type retType = Type.getReturnType(desc);
        if (retType.getSort() != Type.VOID) push(typeCategory(retType));
    }

    void simulateInvokeDynamic(int invokeIndex, String desc) {
        if (lost) return;
        int argEntries = Type.getArgumentTypes(desc).length;
        captureSubStack(invokeIndex, argEntries);
        for (int i = 0; i < argEntries; i++) pop();
        Type retType = Type.getReturnType(desc);
        if (retType.getSort() != Type.VOID) push(typeCategory(retType));
    }

    void simulateJumpInsn(int opcode, Label label) {
        if (lost) return;
        switch (opcode) {
            case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT:
            case Opcodes.IFGE: case Opcodes.IFGT: case Opcodes.IFLE:
            case Opcodes.IFNULL: case Opcodes.IFNONNULL:
                pop();
                saveLabel(label);
                break;
            case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE: case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE: case Opcodes.IF_ICMPGT: case Opcodes.IF_ICMPLE:
            case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE:
                pop(); pop();
                saveLabel(label);
                break;
            case Opcodes.GOTO:
                saveLabel(label);
                markLost();
                break;
            case Opcodes.JSR: push('A'); break;
        }
    }

    void simulateLdc(Object value) {
        if (lost) return;
        if (value instanceof Integer) push('I');
        else if (value instanceof Long) push('J');
        else if (value instanceof Float) push('F');
        else if (value instanceof Double) push('D');
        else push('A'); // String, Type, Handle, ConstantDynamic
    }

    void simulateTableSwitch(Label dflt, Label[] labels) {
        if (!lost) {
            pop();
            saveLabel(dflt);
            for (Label l : labels) saveLabel(l);
        }
        markLost();
    }

    void simulateLookupSwitch(Label dflt, Label[] labels) {
        if (!lost) {
            pop();
            saveLabel(dflt);
            for (Label l : labels) saveLabel(l);
        }
        markLost();
    }

    void simulateMultiANewArray(int numDimensions) {
        if (lost) return;
        for (int i = 0; i < numDimensions; i++) pop();
        push('A');
    }

    void simulateTryCatchBlock(Label handler) {
        List<Character> handlerStack = new ArrayList<>();
        handlerStack.add('A');
        labelStacks.putIfAbsent(handler, handlerStack);
    }

    static char typeCategory(Type t) {
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
}
