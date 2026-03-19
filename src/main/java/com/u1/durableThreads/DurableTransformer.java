package com.u1.durableThreads;

import com.u1.durableThreads.internal.InvokeRegistry;
import com.u1.durableThreads.internal.RawBytecodeScanner;
import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ClassFileTransformer} that injects the replay prologue into every loaded class.
 * Registered by {@link DurableAgent} at JVM startup.
 */
public final class DurableTransformer implements ClassFileTransformer {

    /** Prefixes of classes that must NOT be instrumented. */
    private static final String[] EXCLUDED_PREFIXES = {
            // Shaded dependencies
            "org/objectweb/asm/",
            "org/objenesis/",
            "com/u1/durableThreads/shaded/",
            // JDK internals — COMPUTE_FRAMES can't resolve bootstrap class hierarchies,
            // and instrumenting JDK classes would cause stability issues
            "java/",
            "javax/",
            "jdk/",
            "sun/",
            "com/sun/",
    };

    /** Specific library classes that must NOT be instrumented (to avoid recursion). */
    private static final String[] EXCLUDED_CLASSES = {
            "com/u1/durableThreads/Durable",
            "com/u1/durableThreads/DurableAgent",
            "com/u1/durableThreads/DurableTransformer",
            "com/u1/durableThreads/PrologueInjector",
            "com/u1/durableThreads/ReplayState",
            "com/u1/durableThreads/ThreadFreezer",
            "com/u1/durableThreads/ThreadRestorer",
    };

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;

        // Don't instrument excluded prefixes (JDK, shaded deps)
        for (String prefix : EXCLUDED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return null;
            }
        }

        // Don't instrument specific library classes (to avoid recursion)
        for (String excluded : EXCLUDED_CLASSES) {
            if (className.equals(excluded) || className.startsWith(excluded + "$")) {
                return null;
            }
        }

        // Don't instrument internal subpackages
        if (className.startsWith("com/u1/durableThreads/internal/")
                || className.startsWith("com/u1/durableThreads/snapshot/")
                || className.startsWith("com/u1/durableThreads/exception/")) {
            return null;
        }

        try {
            // Parse the original class
            ClassReader cr = new ClassReader(classfileBuffer);

            // Use COMPUTE_FRAMES so ASM recalculates stack map frames.
            // Use the context classloader for getCommonSuperClass resolution.
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader != null ? loader : ClassLoader.getSystemClassLoader();
                }
            };

            // Inject prologues (SKIP_FRAMES since COMPUTE_FRAMES recomputes all)
            PrologueInjector injector = new PrologueInjector(cw);
            cr.accept(injector, ClassReader.SKIP_FRAMES);

            byte[] instrumented = cw.toByteArray();

            // Store the instrumented bytecode for hash computation
            InvokeRegistry.storeInstrumentedBytecode(className, instrumented);

            // Post-process: analyze the instrumented bytecode to build invoke offset maps.
            // This must not fail — if it does, we still return the instrumented bytes.
            try {
                buildInvokeOffsetMaps(className, instrumented);
            } catch (Exception mapEx) {
                System.err.println("[DurableThreads] Warning: failed to build invoke maps for " +
                        className.replace('/', '.') + ": " + mapEx.getMessage());
            }

            return instrumented;
        } catch (Exception e) {
            // If instrumentation fails for any class, skip it silently.
            System.err.println("[DurableThreads] Warning: failed to instrument " +
                    className.replace('/', '.') + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Analyze the instrumented bytecode to find the exact bytecode positions of
     * invoke instructions that correspond to the PrologueInjector's invoke indices.
     *
     * <p>Uses two independent approaches and cross-checks them:</p>
     * <ol>
     *   <li><b>Raw bytecode scan</b> — parses actual bytes, zero approximation (primary)</li>
     *   <li><b>ASM tree API</b> — walks InsnList, computes sizes (cross-check)</li>
     * </ol>
     */
    private void buildInvokeOffsetMaps(String className, byte[] instrumented) {
        org.objectweb.asm.tree.ClassNode classNode = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(instrumented).accept(classNode, 0);

        for (org.objectweb.asm.tree.MethodNode method : classNode.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            if ("<clinit>".equals(method.name)) continue;
            if ("<init>".equals(method.name)) continue;

            String key = InvokeRegistry.key(className, method.name, method.desc);

            // === PRIMARY: raw bytecode scan ===
            List<Integer> rawOffsets;
            try {
                rawOffsets = RawBytecodeScanner.scanInvokeOffsets(
                        instrumented, method.name, method.desc);
            } catch (Exception e) {
                rawOffsets = null;
            }

            // === SECONDARY: ASM tree API ===
            List<Integer> treeOffsets = computeOffsetsViaTree(method);

            // Both methods must agree on the NUMBER of invokes.
            // BCPs may differ slightly due to ldc/ldc_w promotion (the tree can't
            // predict CP index sizes). The raw scanner is always the ground truth.
            List<Integer> offsets;
            if (rawOffsets != null) {
                offsets = rawOffsets;
                if (!treeOffsets.isEmpty() || !rawOffsets.isEmpty()) {
                    if (rawOffsets.size() != treeOffsets.size()) {
                        // COUNT mismatch is a real bug — the scanners disagree on
                        // which instructions are invokes
                        System.err.println("[DurableThreads] BCP CROSS-CHECK FAILED (count mismatch) for "
                                + className.replace('/', '.') + "." + method.name
                                + "\n  raw =" + rawOffsets
                                + "\n  tree=" + treeOffsets);
                    }
                    // BCP value differences are expected (ldc/ldc_w, wide iinc) —
                    // the raw scanner's values are correct
                }
            } else {
                // Raw scanner crashed — fall back to tree with warning
                offsets = treeOffsets;
                System.err.println("[DurableThreads] Raw scanner failed for "
                        + className.replace('/', '.') + "." + method.name
                        + ", using tree offsets (approximate): " + treeOffsets);
            }

            if (!offsets.isEmpty()) {
                InvokeRegistry.register(key, offsets);
            }
        }
    }

    /**
     * Compute invoke offsets using ASM tree API (cross-check approach).
     * Must produce EXACT same results as the raw bytecode scanner.
     */
    private List<Integer> computeOffsetsViaTree(org.objectweb.asm.tree.MethodNode method) {
        List<Integer> offsets = new ArrayList<>();
        int bcp = 0;
        for (int i = 0; i < method.instructions.size(); i++) {
            org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.get(i);

            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode methodInsn) {
                boolean isReplayStateCall = "com/u1/durableThreads/ReplayState".equals(methodInsn.owner);
                boolean isInitCall = methodInsn.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(methodInsn.name);
                if (!isReplayStateCall && !isInitCall) {
                    offsets.add(bcp);
                }
            } else if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode) {
                offsets.add(bcp);
            }

            bcp += insnSize(insn, bcp);
        }
        return offsets;
    }

    /**
     * Compute the exact bytecode size of an ASM tree instruction, accounting for
     * ClassWriter's short-form optimizations and alignment padding.
     *
     * @param insn the instruction node
     * @param bcp  the current bytecode position (needed for switch alignment)
     */
    private static int insnSize(org.objectweb.asm.tree.AbstractInsnNode insn, int bcp) {
        int opcode = insn.getOpcode();
        if (opcode == -1) return 0; // pseudo-instruction (label, line number, frame)

        // Handle var instructions: ASM normalizes iload_0..iload_3 to ILOAD with
        // operand 0..3, but ClassWriter emits the 1-byte short form for operands 0-3.
        if (insn instanceof org.objectweb.asm.tree.VarInsnNode varInsn) {
            int var = varInsn.var;
            return switch (opcode) {
                case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD,
                     Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE ->
                        var <= 3 ? 1 : 2;
                case Opcodes.RET -> 2;
                default -> 2;
            };
        }

        // Handle IINC: 3 bytes normally, 6 bytes if wide (var > 255 or increment out of byte range)
        if (insn instanceof org.objectweb.asm.tree.IincInsnNode iincInsn) {
            if (iincInsn.var > 255 || iincInsn.incr < -128 || iincInsn.incr > 127) {
                return 6; // wide iinc
            }
            return 3;
        }

        // Handle LDC: ClassWriter may use ldc (2 bytes) or ldc_w (3 bytes)
        // depending on the constant pool index. For most constants, ldc (2) is used.
        if (opcode == Opcodes.LDC) {
            if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldcInsn) {
                // ldc_w is used for long/double (which are actually LDC2_W) and
                // when the CP index > 255. We can't know the CP index from the tree,
                // but long/double always use ldc2_w (3 bytes). For others, assume ldc (2).
                Object cst = ldcInsn.cst;
                if (cst instanceof Long || cst instanceof Double) {
                    return 3; // ldc2_w
                }
            }
            return 2; // ldc
        }

        return switch (opcode) {
            case Opcodes.BIPUSH, Opcodes.NEWARRAY -> 2;
            case Opcodes.SIPUSH -> 3;
            case Opcodes.GOTO, Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
                 Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
                 Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT,
                 Opcodes.IFLE, Opcodes.IFNULL, Opcodes.IFNONNULL,
                 Opcodes.JSR, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
                 Opcodes.GETSTATIC, Opcodes.PUTSTATIC, Opcodes.GETFIELD, Opcodes.PUTFIELD,
                 Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC,
                 Opcodes.NEW, Opcodes.ANEWARRAY, Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> 3;
            case Opcodes.INVOKEINTERFACE, Opcodes.INVOKEDYNAMIC -> 5;
            case Opcodes.MULTIANEWARRAY -> 4;
            case Opcodes.TABLESWITCH -> {
                var ts = (org.objectweb.asm.tree.TableSwitchInsnNode) insn;
                int padding = (4 - ((bcp + 1) % 4)) % 4;
                int cases = ts.labels.size();
                yield 1 + padding + 12 + 4 * cases;
            }
            case Opcodes.LOOKUPSWITCH -> {
                var ls = (org.objectweb.asm.tree.LookupSwitchInsnNode) insn;
                int padding = (4 - ((bcp + 1) % 4)) % 4;
                int pairs = ls.labels.size();
                yield 1 + padding + 8 + 8 * pairs;
            }
            default -> 1;
        };
    }
}
