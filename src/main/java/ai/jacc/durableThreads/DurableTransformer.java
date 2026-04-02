package ai.jacc.durableThreads;

import ai.jacc.durableThreads.internal.InvokeRegistry;
import ai.jacc.durableThreads.internal.RawBytecodeScanner;
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
            "ai/jacc/durableThreads/shaded/",
            // The library's own package — instrumenting these causes recursion.
            // New classes added to this package are automatically excluded.
            // Subpackages that contain user test code (e.g. e2e/) are whitelisted
            // below in WHITELISTED_PREFIXES.
            "ai/jacc/durableThreads/",
            // JDK internals — COMPUTE_FRAMES can't resolve bootstrap class hierarchies,
            // and instrumenting JDK classes would cause stability issues
            "java/",
            "javax/",
            "jdk/",
            "sun/",
            "com/sun/",
            // IDE runtime classes — not user code, complex bytecode patterns
            "com/intellij/",
    };

    /** Subpackages under an excluded prefix that SHOULD be instrumented (user test code). */
    private static final String[] WHITELISTED_PREFIXES = {
            "ai/jacc/durableThreads/e2e/",
    };

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;

        // Whitelist check: some subpackages under excluded prefixes contain
        // user code that must be instrumented (e.g. E2E test programs).
        boolean whitelisted = false;
        for (String prefix : WHITELISTED_PREFIXES) {
            if (className.startsWith(prefix)) {
                whitelisted = true;
                break;
            }
        }

        // Don't instrument excluded prefixes (unless whitelisted)
        if (!whitelisted) {
            for (String prefix : EXCLUDED_PREFIXES) {
                if (className.startsWith(prefix)) {
                    return null;
                }
            }
        }

        try {
            // Cache original (pre-instrumentation) bytecode for hash validation
            // and future snapshot embedding. Must happen before instrumentation.
            InvokeRegistry.storeOriginalBytecode(className, classfileBuffer.clone());

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

            // Store the instrumented bytecode for operand stack checking
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
            List<Integer> allOffsets = RawBytecodeScanner.scanInvokeOffsets(
                    instrumented, method.name, method.desc);

            // With the single-pass direct-jump architecture, resume stubs
            // jump to BEFORE_INVOKE labels (GOTO) instead of re-invoking methods.
            // So the scanner finds ONLY original-code invokes — no stub invokes.
            // The last `originalCount` entries are the original-code invokes.
            if (allOffsets.size() < originalCount) {
                throw new RuntimeException(
                        "[DurableThreads] BUG: scanner found " + allOffsets.size()
                        + " invokes but PrologueInjector expects " + originalCount
                        + " for " + className.replace('/', '.') + "." + method.name
                        + ". This indicates an inconsistency between the injector "
                        + "and scanner invoke filtering.");
            }
            List<Integer> originalOffsets = allOffsets.subList(
                    allOffsets.size() - originalCount, allOffsets.size());

            if (!originalOffsets.isEmpty()) {
                InvokeRegistry.register(key, new ArrayList<>(originalOffsets));
            }
        }
    }

}
