package com.u1.durableThreads;

import com.u1.durableThreads.internal.InvokeRegistry;
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

            // Post-process: analyze the instrumented bytecode to build invoke offset maps
            buildInvokeOffsetMaps(className, instrumented);

            return instrumented;
        } catch (Exception e) {
            // If instrumentation fails for any class, skip it silently.
            // This can happen for unusual bytecode patterns.
            System.err.println("[DurableThreads] Warning: failed to instrument " +
                    className.replace('/', '.') + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Analyze the instrumented bytecode to find the bytecode offsets of invoke
     * instructions that correspond to the PrologueInjector's invoke indices.
     *
     * <p>We use ASM's tree API to iterate instructions and compute BCPs.
     * We match the same filtering logic as PrologueInjector: skip ReplayState
     * calls and invokespecial &lt;init&gt; calls.</p>
     */
    private void buildInvokeOffsetMaps(String className, byte[] instrumented) {
        org.objectweb.asm.tree.ClassNode classNode = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(instrumented).accept(classNode, 0);

        for (org.objectweb.asm.tree.MethodNode method : classNode.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            if ("<clinit>".equals(method.name)) continue;
            if ("<init>".equals(method.name)) continue;

            String key = InvokeRegistry.key(className, method.name, method.desc);
            List<Integer> offsets = new ArrayList<>();

            // Compute BCP for each instruction by accumulating sizes
            int bcp = 0;
            for (int i = 0; i < method.instructions.size(); i++) {
                org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.get(i);

                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode methodInsn) {
                    // Skip ReplayState calls (prologue internals)
                    boolean isReplayStateCall = "com/u1/durableThreads/ReplayState".equals(methodInsn.owner)
                            || "com/u1/durableThreads/shaded/asm".equals(methodInsn.owner);
                    // Skip invokespecial <init> (matches PrologueInjector exclusion)
                    boolean isInitCall = methodInsn.getOpcode() == Opcodes.INVOKESPECIAL
                            && "<init>".equals(methodInsn.name);

                    if (!isReplayStateCall && !isInitCall) {
                        offsets.add(bcp);
                    }
                } else if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode) {
                    offsets.add(bcp);
                }

                bcp += insnSize(insn);
            }

            if (!offsets.isEmpty()) {
                InvokeRegistry.register(key, offsets);
            }
        }
    }

    /**
     * Compute the bytecode size of an instruction for BCP tracking.
     */
    private static int insnSize(org.objectweb.asm.tree.AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == -1) return 0; // pseudo-instruction (label, line number, frame)

        return switch (opcode) {
            case Opcodes.BIPUSH, Opcodes.NEWARRAY, Opcodes.LDC -> 2;
            case Opcodes.SIPUSH, Opcodes.IINC -> 3;
            case Opcodes.GOTO, Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
                 Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
                 Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT,
                 Opcodes.IFLE, Opcodes.IFNULL, Opcodes.IFNONNULL,
                 Opcodes.JSR, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
                 Opcodes.GETSTATIC, Opcodes.PUTSTATIC, Opcodes.GETFIELD, Opcodes.PUTFIELD,
                 Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC,
                 Opcodes.NEW, Opcodes.ANEWARRAY, Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> 3;
            case Opcodes.INVOKEINTERFACE, Opcodes.INVOKEDYNAMIC, Opcodes.MULTIANEWARRAY -> 5;
            case Opcodes.TABLESWITCH -> {
                // Variable size: 0-3 padding + 4*3 + 4*N
                var ts = (org.objectweb.asm.tree.TableSwitchInsnNode) insn;
                int cases = ts.labels.size();
                yield 1 + 3 + 12 + 4 * cases; // approximate (alignment padding varies)
            }
            case Opcodes.LOOKUPSWITCH -> {
                var ls = (org.objectweb.asm.tree.LookupSwitchInsnNode) insn;
                int pairs = ls.labels.size();
                yield 1 + 3 + 8 + 8 * pairs; // approximate
            }
            default -> 1;
        };
    }
}
