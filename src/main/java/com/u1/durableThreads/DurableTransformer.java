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
            // Our own library and shaded dependencies
            "com/u1/durableThreads/",
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

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;

        // Don't instrument our own classes or core dependencies
        for (String prefix : EXCLUDED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return null;
            }
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
     * Analyze the instrumented bytecode to find the bytecode offsets of invoke instructions.
     * These offsets are registered in {@link InvokeRegistry} for use during freeze.
     */
    private void buildInvokeOffsetMaps(String className, byte[] instrumented) {
        ClassReader cr = new ClassReader(instrumented);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return null;
                }
                if ("<clinit>".equals(name)) {
                    return null;
                }

                String key = InvokeRegistry.key(className, name, descriptor);
                List<Integer> offsets = new ArrayList<>();

                return new MethodVisitor(Opcodes.ASM9) {
                    private int currentOffset = 0;
                    private boolean inOriginalCode = false;
                    // We need to track bytecode offsets. ASM doesn't directly give us
                    // offsets during visiting, but we can use Label offsets after visitEnd.
                    // For simplicity, we'll collect all invoke instructions and use
                    // their ordering. The actual BCP mapping will be resolved at freeze
                    // time by re-analyzing the bytecode.

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        // Skip our own prologue calls (to ReplayState)
                        if (!"com/u1/durableThreads/ReplayState".equals(owner)) {
                            offsets.add(offsets.size()); // index-based for now
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        offsets.add(offsets.size());
                    }

                    @Override
                    public void visitEnd() {
                        if (!offsets.isEmpty()) {
                            InvokeRegistry.register(key, offsets);
                        }
                    }
                };
            }
        }, 0);
    }
}
