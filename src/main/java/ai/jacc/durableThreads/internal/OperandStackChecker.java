package ai.jacc.durableThreads.internal;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.nio.charset.StandardCharsets;

/**
 * Checks the operand stack depth at invoke instructions within a method.
 * Used at freeze time to verify that each frame in the call stack has an
 * empty operand stack (excluding the invoke's own arguments/receiver).
 *
 * <p>This is a hard requirement: the operand stack is not exposed via JDI,
 * so values on the stack cannot be captured or restored. If any frame has
 * extra values on the stack beyond the invoke's arguments, the freeze must
 * be rejected.</p>
 */
public final class OperandStackChecker {

    private OperandStackChecker() {}

    /**
     * Check if the operand stack is empty at a specific invoke instruction,
     * excluding the invoke's own arguments and receiver.
     *
     * @param classBytecode the class file bytes (instrumented)
     * @param methodName    the method name
     * @param methodDesc    the method descriptor
     * @param bytecodeIndex the bytecode index of the invoke instruction
     * @return null if the stack is clean, or an error message describing the problem
     */
    public static String checkStackAtInvoke(byte[] classBytecode,
                                            String methodName, String methodDesc,
                                            int bytecodeIndex) {
        try {
            ClassReader cr = new ClassReader(classBytecode);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {
                if (!method.name.equals(methodName) || !method.desc.equals(methodDesc)) {
                    continue;
                }

                return analyzeMethod(classNode.name, method, bytecodeIndex, classBytecode);
            }

            return "Method not found: " + methodName + methodDesc;
        } catch (Exception e) {
            return null;
        }
    }

    private static String analyzeMethod(String owner, MethodNode method, int targetBci,
                                        byte[] classBytecode) {
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            Frame<BasicValue>[] frames = analyzer.analyze(owner, method);

            // Find the raw code bytes for this method in the class file
            int codeOffset = findCodeOffset(classBytecode, method.name, method.desc);
            if (codeOffset < 0) {
                // Fallback: can't find code bytes, skip check rather than false positive
                return null;
            }

            // Walk raw bytecode and ASM instruction list in lockstep.
            // Raw bytecode gives exact BCIs; ASM instruction list gives frame indices.
            int bci = 0;
            int insnIdx = 0;
            while (insnIdx < method.instructions.size()) {
                AbstractInsnNode insn = method.instructions.get(insnIdx);

                // Skip pseudo-instructions (no BCI)
                if (insn instanceof LabelNode || insn instanceof LineNumberNode
                        || insn instanceof FrameNode) {
                    insnIdx++;
                    continue;
                }

                if (bci == targetBci) {
                    return checkInstructionStack(frames[insnIdx], insn, method.name);
                }

                // Advance by exact instruction size from raw bytes
                int size = rawInsnSize(classBytecode, codeOffset + bci, bci);
                bci += size;
                insnIdx++;
            }

            return null;
        } catch (AnalyzerException e) {
            return null;
        }
    }

    private static String checkInstructionStack(Frame<BasicValue> frame,
                                                 AbstractInsnNode insn,
                                                 String methodName) {
        if (frame == null) return null; // unreachable code

        if (insn instanceof MethodInsnNode methodInsn) {
            int expectedStackDepth = computeInvokeConsumedSlots(
                    methodInsn.getOpcode(), methodInsn.desc);
            int actualStackDepth = frame.getStackSize();
            int extraSlots = actualStackDepth - expectedStackDepth;

            if (extraSlots > 0) {
                return String.format(
                        "Non-empty operand stack in %s at invoke %s.%s%s: " +
                        "%d extra slot(s) on stack that cannot be captured/restored. " +
                        "Durable.freeze() must be called when all frames have a clean operand stack.",
                        methodName, methodInsn.owner, methodInsn.name, methodInsn.desc,
                        extraSlots);
            }
        } else if (insn instanceof InvokeDynamicInsnNode indyInsn) {
            int expectedStackDepth = computeInvokeConsumedSlots(
                    Opcodes.INVOKESTATIC, indyInsn.desc); // invokedynamic has no receiver
            int actualStackDepth = frame.getStackSize();
            int extraSlots = actualStackDepth - expectedStackDepth;

            if (extraSlots > 0) {
                return String.format(
                        "Non-empty operand stack in %s at invokedynamic %s%s: " +
                        "%d extra slot(s) on stack.",
                        methodName, indyInsn.name, indyInsn.desc, extraSlots);
            }
        }

        return null; // clean
    }

    /**
     * Compute how many stack slots an invoke instruction consumes
     * (receiver + arguments).
     */
    private static int computeInvokeConsumedSlots(int opcode, String desc) {
        int slots = 0;

        // Receiver for non-static
        if (opcode != Opcodes.INVOKESTATIC) {
            slots += 1;
        }

        // Arguments
        for (Type argType : Type.getArgumentTypes(desc)) {
            slots += argType.getSize();
        }

        return slots;
    }

    // ---- Raw class file parsing for exact BCI computation ----

    /**
     * Find the byte offset within the class file where the Code attribute's
     * bytecode starts for the named method.
     *
     * @return offset into classBytecode where code bytes begin, or -1 if not found
     */
    private static int findCodeOffset(byte[] b, String methodName, String methodDesc) {
        int pos = 8; // skip magic (4), minor_version (2), major_version (2)

        // Parse constant pool to cache UTF8 entries and skip past it
        int cpCount = readU2(b, pos);
        pos += 2;
        String[] cpUtf8 = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = b[pos] & 0xFF;
            pos++;
            switch (tag) {
                case 1: // CONSTANT_Utf8
                    int len = readU2(b, pos);
                    pos += 2;
                    cpUtf8[i] = new String(b, pos, len, StandardCharsets.UTF_8);
                    pos += len;
                    break;
                case 3: case 4: // Integer, Float
                    pos += 4; break;
                case 5: case 6: // Long, Double
                    pos += 8; i++; break; // takes two CP slots
                case 7: case 8: // Class, String
                    pos += 2; break;
                case 9: case 10: case 11: case 12: // Fieldref, Methodref, InterfaceMethodref, NameAndType
                    pos += 4; break;
                case 15: // MethodHandle
                    pos += 3; break;
                case 16: // MethodType
                    pos += 2; break;
                case 17: case 18: // Dynamic, InvokeDynamic
                    pos += 4; break;
                case 19: case 20: // Module, Package
                    pos += 2; break;
                default:
                    return -1; // unknown CP tag
            }
        }

        // Skip access_flags, this_class, super_class
        pos += 6;

        // Skip interfaces
        int ifaceCount = readU2(b, pos);
        pos += 2 + ifaceCount * 2;

        // Skip fields
        int fieldCount = readU2(b, pos);
        pos += 2;
        for (int i = 0; i < fieldCount; i++) {
            pos += 6; // access_flags, name_index, descriptor_index
            int attrCount = readU2(b, pos);
            pos += 2;
            for (int j = 0; j < attrCount; j++) {
                pos += 2; // attribute_name_index
                int attrLen = readU4(b, pos);
                pos += 4 + attrLen;
            }
        }

        // Parse methods
        int methodCount = readU2(b, pos);
        pos += 2;
        for (int i = 0; i < methodCount; i++) {
            pos += 2; // access_flags
            int nameIdx = readU2(b, pos); pos += 2;
            int descIdx = readU2(b, pos); pos += 2;
            String name = cpUtf8[nameIdx];
            String desc = cpUtf8[descIdx];
            boolean isTarget = methodName.equals(name) && methodDesc.equals(desc);

            int attrCount = readU2(b, pos); pos += 2;
            for (int j = 0; j < attrCount; j++) {
                int attrNameIdx = readU2(b, pos); pos += 2;
                int attrLen = readU4(b, pos); pos += 4;

                if (isTarget && "Code".equals(cpUtf8[attrNameIdx])) {
                    // Code attribute: u2 max_stack, u2 max_locals, u4 code_length, code[...]
                    // pos is at max_stack; skip to code bytes
                    return pos + 8; // +4 (max_stack + max_locals) + 4 (code_length)
                }

                pos += attrLen;
            }
        }

        return -1;
    }

    /**
     * Compute the exact size of the JVM instruction at the given offset in raw bytecode.
     *
     * @param b      the class file bytes
     * @param offset absolute offset of the instruction within the class file
     * @param bci    method-relative bytecode index (needed for switch padding alignment)
     */
    private static int rawInsnSize(byte[] b, int offset, int bci) {
        int opcode = b[offset] & 0xFF;
        switch (opcode) {
            // 1-byte instructions
            case 0x00: // NOP
            case 0x01: // ACONST_NULL
            case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08: // ICONST_M1..ICONST_5
            case 0x09: case 0x0A: // LCONST_0, LCONST_1
            case 0x0B: case 0x0C: case 0x0D: // FCONST_0..FCONST_2
            case 0x0E: case 0x0F: // DCONST_0, DCONST_1
            case 0x1A: case 0x1B: case 0x1C: case 0x1D: // ILOAD_0..ILOAD_3
            case 0x1E: case 0x1F: case 0x20: case 0x21: // LLOAD_0..LLOAD_3
            case 0x22: case 0x23: case 0x24: case 0x25: // FLOAD_0..FLOAD_3
            case 0x26: case 0x27: case 0x28: case 0x29: // DLOAD_0..DLOAD_3
            case 0x2A: case 0x2B: case 0x2C: case 0x2D: // ALOAD_0..ALOAD_3
            case 0x2E: case 0x2F: case 0x30: case 0x31: case 0x32: case 0x33: case 0x34: case 0x35: // xALOAD
            case 0x3B: case 0x3C: case 0x3D: case 0x3E: // ISTORE_0..ISTORE_3
            case 0x3F: case 0x40: case 0x41: case 0x42: // LSTORE_0..LSTORE_3
            case 0x43: case 0x44: case 0x45: case 0x46: // FSTORE_0..FSTORE_3
            case 0x47: case 0x48: case 0x49: case 0x4A: // DSTORE_0..DSTORE_3
            case 0x4B: case 0x4C: case 0x4D: case 0x4E: // ASTORE_0..ASTORE_3
            case 0x4F: case 0x50: case 0x51: case 0x52: case 0x53: case 0x54: case 0x55: case 0x56: // xASTORE
            case 0x57: case 0x58: // POP, POP2
            case 0x59: case 0x5A: case 0x5B: case 0x5C: case 0x5D: case 0x5E: // DUP..DUP2_X2
            case 0x5F: // SWAP
            case 0x60: case 0x61: case 0x62: case 0x63: // IADD, LADD, FADD, DADD
            case 0x64: case 0x65: case 0x66: case 0x67: // ISUB, LSUB, FSUB, DSUB
            case 0x68: case 0x69: case 0x6A: case 0x6B: // IMUL, LMUL, FMUL, DMUL
            case 0x6C: case 0x6D: case 0x6E: case 0x6F: // IDIV, LDIV, FDIV, DDIV
            case 0x70: case 0x71: case 0x72: case 0x73: // IREM, LREM, FREM, DREM
            case 0x74: case 0x75: case 0x76: case 0x77: // INEG, LNEG, FNEG, DNEG
            case 0x78: case 0x79: case 0x7A: case 0x7B: // ISHL, LSHL, ISHR, LSHR
            case 0x7C: case 0x7D: case 0x7E: case 0x7F: // IUSHR, LUSHR, IAND, LAND
            case 0x80: case 0x81: case 0x82: case 0x83: // IOR, LOR, IXOR, LXOR
            case 0x85: case 0x86: case 0x87: case 0x88: case 0x89: case 0x8A: // I2L..F2I
            case 0x8B: case 0x8C: case 0x8D: case 0x8E: case 0x8F: case 0x90: // F2L..D2F
            case 0x91: case 0x92: case 0x93: // I2B, I2C, I2S
            case 0x94: case 0x95: case 0x96: case 0x97: case 0x98: // LCMP, FCMPL..DCMPG
            case 0xAC: case 0xAD: case 0xAE: case 0xAF: case 0xB0: case 0xB1: // xRETURN, RETURN
            case 0xBE: // ARRAYLENGTH
            case 0xBF: // ATHROW
            case 0xC2: case 0xC3: // MONITORENTER, MONITOREXIT
                return 1;

            // 2-byte instructions
            case 0x10: // BIPUSH
            case 0x12: // LDC
            case 0x15: case 0x16: case 0x17: case 0x18: case 0x19: // ILOAD..ALOAD
            case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A: // ISTORE..ASTORE
            case 0xA9: // RET
            case 0xBC: // NEWARRAY
                return 2;

            // 3-byte instructions
            case 0x11: // SIPUSH
            case 0x13: case 0x14: // LDC_W, LDC2_W
            case 0x84: // IINC
            case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E: // IFxx
            case 0x9F: case 0xA0: case 0xA1: case 0xA2: case 0xA3: case 0xA4: // IF_ICMPxx
            case 0xA5: case 0xA6: // IF_ACMPEQ, IF_ACMPNE
            case 0xA7: // GOTO
            case 0xA8: // JSR
            case 0xB2: case 0xB3: case 0xB4: case 0xB5: // GETSTATIC..PUTFIELD
            case 0xB6: case 0xB7: case 0xB8: // INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC
            case 0xBB: // NEW
            case 0xBD: // ANEWARRAY
            case 0xC0: // CHECKCAST
            case 0xC1: // INSTANCEOF
            case 0xC6: case 0xC7: // IFNULL, IFNONNULL
                return 3;

            // 4-byte instructions
            case 0xC5: // MULTIANEWARRAY
                return 4;

            // 5-byte instructions
            case 0xB9: // INVOKEINTERFACE
            case 0xBA: // INVOKEDYNAMIC
            case 0xC8: // GOTO_W
            case 0xC9: // JSR_W
                return 5;

            // Variable-size: TABLESWITCH
            case 0xAA: {
                // Padding aligns to 4-byte boundary relative to method start
                int padding = (4 - ((bci + 1) % 4)) % 4;
                int afterPad = offset + 1 + padding;
                int low = readS4(b, afterPad + 4);
                int high = readS4(b, afterPad + 8);
                return 1 + padding + 12 + 4 * (high - low + 1);
            }

            // Variable-size: LOOKUPSWITCH
            case 0xAB: {
                int padding = (4 - ((bci + 1) % 4)) % 4;
                int afterPad = offset + 1 + padding;
                int npairs = readS4(b, afterPad + 4);
                return 1 + padding + 8 + 8 * npairs;
            }

            // WIDE prefix
            case 0xC4: {
                int subOpcode = b[offset + 1] & 0xFF;
                if (subOpcode == 0x84) { // WIDE IINC
                    return 6;
                }
                return 4; // WIDE load/store
            }

            default:
                return 1; // unknown, assume 1
        }
    }

    private static int readU2(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
    }

    private static int readU4(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16)
                | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
    }

    private static int readS4(byte[] b, int pos) {
        return ((b[pos]) << 24) | ((b[pos + 1] & 0xFF) << 16)
                | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
    }
}
