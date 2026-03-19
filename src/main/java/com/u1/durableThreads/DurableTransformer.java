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
            buildInvokeOffsetMaps(className, instrumented, injector);

            return instrumented;
        } catch (Exception e) {
            throw new RuntimeException(
                    "[DurableThreads] Failed to instrument class "
                    + className.replace('/', '.') + ": " + e.getMessage(), e);
        }
    }

    /**
     * Analyze the instrumented bytecode to find the exact bytecode positions of
     * invoke instructions that correspond to the PrologueInjector's invoke indices.
     *
     * <p>The raw bytecode scanner finds ALL user invokes (including those in
     * resume stubs). PrologueInjector tells us how many original-code invokes
     * exist. Since stubs always precede the original code section in the
     * bytecode, the last N entries in the scanner's list are the original-code
     * invokes — exactly matching PrologueInjector's index 0..N-1.</p>
     */
    private void buildInvokeOffsetMaps(String className, byte[] instrumented,
                                       PrologueInjector injector) {
        // Walk method descriptors to find scannable methods.
        // We use the tree API only to enumerate methods — BCPs come from the raw scanner.
        org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(instrumented).accept(cn, ClassReader.SKIP_CODE);

        for (org.objectweb.asm.tree.MethodNode method : cn.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            if ("<clinit>".equals(method.name)) continue;
            if ("<init>".equals(method.name)) continue;

            String key = InvokeRegistry.key(className, method.name, method.desc);

            // PrologueInjector is the single source of truth for how many
            // original-code invokes exist.  Resume stubs can add extra user
            // invokes, but they always appear BEFORE the original code section
            // in the bytecode.
            int originalCount = injector.getOriginalInvokeCount(method.name, method.desc);

            // === PRIMARY (exact): raw bytecode scan ===
            // Scans ALL user invokes in the instrumented bytecode (stubs + original).
            List<Integer> allOffsets;
            try {
                allOffsets = RawBytecodeScanner.scanInvokeOffsets(
                        instrumented, method.name, method.desc);
            } catch (Exception e) {
                System.err.println("[DurableThreads] Raw bytecode scanner failed for "
                        + className.replace('/', '.') + "." + method.name + ": " + e);
                allOffsets = List.of();
            }

            // The last `originalCount` entries are the original-code invokes.
            // Everything before them are resume-stub re-invokes.
            if (allOffsets.size() < originalCount) {
                System.err.println("[DurableThreads] BUG: scanner found " + allOffsets.size()
                        + " invokes but PrologueInjector expects " + originalCount
                        + " for " + className.replace('/', '.') + "." + method.name);
                continue;
            }
            List<Integer> offsets = allOffsets.subList(
                    allOffsets.size() - originalCount, allOffsets.size());

            if (!offsets.isEmpty()) {
                InvokeRegistry.register(key, new ArrayList<>(offsets));
            }
        }
    }

}
