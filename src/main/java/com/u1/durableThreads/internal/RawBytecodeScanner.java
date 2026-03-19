package com.u1.durableThreads.internal;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans raw bytecode bytes to find exact bytecode positions (BCPs) of invoke
 * instructions. Uses ASM to locate the method's Code attribute, then reads
 * the raw bytes directly for exact BCP computation.
 *
 * <p>This is the ground-truth approach. The alternative (computing BCPs by
 * summing instruction sizes from ASM's tree API) is approximate because
 * tableswitch/lookupswitch have alignment-dependent padding. Both approaches
 * are run and cross-checked.</p>
 */
public final class RawBytecodeScanner {

    private RawBytecodeScanner() {}

    /** An invoke instruction found in the bytecode. */
    public record InvokeLocation(int bcp, int opcode) {}

    /**
     * Scan a method's bytecode for invoke instructions, returning exact BCPs.
     * Skips ReplayState calls and invokespecial &lt;init&gt; (matching
     * PrologueInjector's invoke index assignment).
     *
     * @param classBytes the complete class file bytes
     * @param methodName the method to scan
     * @param methodDesc the method descriptor
     * @return list of invoke BCPs in order of appearance
     */
    public static List<Integer> scanInvokeOffsets(byte[] classBytes,
                                                   String methodName, String methodDesc) {
        List<Integer> results = new ArrayList<>();

        // Use ASM to find the Code attribute's raw bytes for the target method.
        // ClassReader gives us the method body as a byte range within classBytes.
        ClassReader cr = new ClassReader(classBytes);

        // We need access to the constant pool to resolve method owners.
        // Build a quick index of the constant pool.
        int cpCount = cr.readUnsignedShort(8);

        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals(methodName) || !descriptor.equals(methodDesc)) return null;
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return null;

                // Return a MethodVisitor that will receive visitAttribute for Code.
                // But actually, we can't easily get raw Code bytes via the visitor API.
                // Instead, we'll use the MethodNode to get the instruction list,
                // serialize it to bytes via ClassWriter, then scan the bytes.
                return null;
            }
        }, ClassReader.SKIP_CODE);

        // Better approach: use ClassReader's internal structure.
        // The class file format is: ... methods[count] { access(2), name(2), desc(2), attrs }
        // Each method's Code attribute contains the raw bytecode.
        // Let's walk the class file to find our method's Code.

        scanClassFileForMethodCode(classBytes, methodName, methodDesc, results);

        return results;
    }

    /**
     * Walk the class file binary to find the target method's Code attribute
     * and scan its bytecode for invoke instructions.
     */
    private static void scanClassFileForMethodCode(byte[] cf, String targetMethod,
                                                    String targetDesc,
                                                    List<Integer> results) {
        int pos = 10; // skip magic(4) + version(4) + cp_count(2)

        // Skip constant pool
        int cpCount = readU2(cf, 8);
        // We need the CP to resolve method references. Build a minimal index.
        String[] utf8 = new String[cpCount];
        int[] classNameIdx = new int[cpCount];
        int[] refClassIdx = new int[cpCount];
        int[] refNatIdx = new int[cpCount];
        int[] natNameIdx = new int[cpCount];

        for (int i = 1; i < cpCount; i++) {
            int tag = cf[pos++] & 0xFF;
            switch (tag) {
                case 1: // Utf8
                    int len = readU2(cf, pos); pos += 2;
                    utf8[i] = new String(cf, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                    pos += len;
                    break;
                case 7: // Class
                    classNameIdx[i] = readU2(cf, pos); pos += 2;
                    break;
                case 9: case 10: case 11: // Field/Method/InterfaceMethodRef
                    refClassIdx[i] = readU2(cf, pos);
                    refNatIdx[i] = readU2(cf, pos + 2);
                    pos += 4;
                    break;
                case 12: // NameAndType
                    natNameIdx[i] = readU2(cf, pos);
                    pos += 4;
                    break;
                case 3: case 4: pos += 4; break;  // Int, Float
                case 5: case 6: pos += 8; i++; break; // Long, Double (takes 2 slots)
                case 8: pos += 2; break; // String
                case 15: pos += 3; break; // MethodHandle
                case 16: pos += 2; break; // MethodType
                case 17: case 18: pos += 4; break; // Dynamic, InvokeDynamic
                case 19: case 20: pos += 2; break; // Module, Package
                default: return; // Unknown tag — bail
            }
        }

        // Skip access(2) + this_class(2) + super_class(2)
        pos += 6;

        // Skip interfaces
        int ifCount = readU2(cf, pos); pos += 2 + ifCount * 2;

        // Skip fields
        int fieldCount = readU2(cf, pos); pos += 2;
        for (int i = 0; i < fieldCount; i++) {
            pos += 6;
            int attrCount = readU2(cf, pos); pos += 2;
            for (int j = 0; j < attrCount; j++) {
                pos += 2;
                int attrLen = readI4(cf, pos); pos += 4 + attrLen;
            }
        }

        // Methods
        int methodCount = readU2(cf, pos); pos += 2;
        for (int m = 0; m < methodCount; m++) {
            int nameIdx = readU2(cf, pos + 2);
            int descIdx = readU2(cf, pos + 4);
            pos += 6;
            String mName = utf8[nameIdx];
            String mDesc = utf8[descIdx];
            boolean isTarget = targetMethod.equals(mName) && targetDesc.equals(mDesc);

            int attrCount = readU2(cf, pos); pos += 2;
            for (int a = 0; a < attrCount; a++) {
                int attrNameIdx = readU2(cf, pos);
                int attrLen = readI4(cf, pos + 2);
                pos += 6;

                if (isTarget && "Code".equals(utf8[attrNameIdx])) {
                    int codeLength = readI4(cf, pos + 4);
                    int codeStart = pos + 8;
                    scanBytecodeBytes(cf, codeStart, codeLength,
                            utf8, classNameIdx, refClassIdx, refNatIdx, natNameIdx,
                            results);
                }
                pos += attrLen;
            }
        }
    }

    /**
     * Scan raw bytecode bytes for invoke instructions, using exact byte counting.
     */
    private static void scanBytecodeBytes(byte[] code, int codeStart, int codeLen,
                                           String[] utf8, int[] classNameIdx,
                                           int[] refClassIdx, int[] refNatIdx,
                                           int[] natNameIdx,
                                           List<Integer> results) {
        int end = codeStart + codeLen;
        int pc = codeStart;

        while (pc < end) {
            int bcp = pc - codeStart;
            int op = code[pc] & 0xFF;

            boolean isInvoke = (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKESPECIAL
                    || op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEINTERFACE);

            if (isInvoke) {
                int cpIdx = readU2(code, pc + 1);
                // Resolve owner and name
                int classIdx = refClassIdx[cpIdx];
                String owner = utf8[classNameIdx[classIdx]];
                int natIdx = refNatIdx[cpIdx];
                String name = utf8[natNameIdx[natIdx]];

                // Apply PrologueInjector filters
                boolean skip = "com/u1/durableThreads/ReplayState".equals(owner)
                        || (op == Opcodes.INVOKESPECIAL && "<init>".equals(name));

                if (!skip) {
                    results.add(bcp);
                }
            } else if (op == Opcodes.INVOKEDYNAMIC) {
                results.add(bcp);
            }

            pc += opcodeSize(code, pc, op);
        }
    }

    /** Exact bytecode instruction size (reads actual bytes for variable-length instructions). */
    private static int opcodeSize(byte[] code, int pc, int opcode) {
        return switch (opcode) {
            // 2-byte
            case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, // bipush, ldc, iload..aload
                 0x36, 0x37, 0x38, 0x39, 0x3a, // istore..astore
                 0xa9, 0xbc -> 2; // ret, newarray
            // 3-byte
            case 0x11, 0x13, 0x14, 0x84, // sipush, ldc_w, ldc2_w, iinc
                 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, // if*
                 0x9f, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, // if_icmp*, if_acmp*
                 0xa7, 0xa8, // goto, jsr
                 0xb2, 0xb3, 0xb4, 0xb5, // getstatic..putfield
                 0xb6, 0xb7, 0xb8, // invoke{virtual,special,static}
                 0xbb, 0xbd, 0xc0, 0xc1, // new, anewarray, checkcast, instanceof
                 0xc6, 0xc7 -> 3; // ifnull, ifnonnull
            // 4-byte
            case 0xc5 -> 4; // multianewarray
            // 5-byte
            case 0xb9, 0xba, 0xc8, 0xc9 -> 5; // invokeinterface, invokedynamic, goto_w, jsr_w
            // wide
            case 0xc4 -> (code[pc + 1] & 0xFF) == 0x84 ? 6 : 4;
            // tableswitch (variable, alignment-padded)
            case 0xaa -> {
                int aligned = (pc + 4) & ~3;
                int low = readI4(code, aligned + 4);
                int high = readI4(code, aligned + 8);
                yield (aligned - pc) + 12 + (high - low + 1) * 4;
            }
            // lookupswitch (variable, alignment-padded)
            case 0xab -> {
                int aligned = (pc + 4) & ~3;
                int npairs = readI4(code, aligned + 4);
                yield (aligned - pc) + 8 + npairs * 8;
            }
            // All other instructions are 1 byte
            default -> 1;
        };
    }

    private static int readU2(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    /** Read 4 bytes as int. For attribute lengths this is always non-negative in practice. */
    private static int readI4(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
