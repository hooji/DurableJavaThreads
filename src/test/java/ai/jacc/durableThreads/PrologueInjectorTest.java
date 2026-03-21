package ai.jacc.durableThreads;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PrologueInjectorTest {

    /**
     * A simple target class for instrumentation testing.
     * We compile this normally, then run the PrologueInjector over its bytecode.
     */
    static class SampleTarget {
        public int add(int a, int b) {
            return a + b;
        }

        public String greet(String name) {
            return "Hello, " + name;
        }

        public void callsMultipleMethods() {
            String s = "test";
            int len = s.length();
            String upper = s.toUpperCase();
            System.out.println(upper + len);
        }

        public static void staticMethod(int x) {
            System.out.println(x);
        }
    }

    @Test
    void instrumentedClassLoadsSuccessfully() throws Exception {
        byte[] original = loadClassBytes(SampleTarget.class);
        byte[] instrumented = instrument(original);

        assertNotNull(instrumented);
        assertTrue(instrumented.length > original.length,
                "Instrumented bytecode should be larger due to prologue");
    }

    @Test
    void instrumentedClassPassesVerification() throws Exception {
        byte[] original = loadClassBytes(SampleTarget.class);
        byte[] instrumented = instrument(original);

        // Verify the bytecode is valid by running ASM's CheckClassAdapter
        ClassReader cr = new ClassReader(instrumented);
        // This will throw if the bytecode is invalid
        cr.accept(new org.objectweb.asm.util.CheckClassAdapter(
                new ClassVisitor(Opcodes.ASM9) {}), 0);
    }

    @Test
    void prologueInjectsReplayCheck() throws Exception {
        byte[] original = loadClassBytes(SampleTarget.class);
        byte[] instrumented = instrument(original);

        // Verify that isReplayThread is called in each non-trivial method
        List<String> calledMethods = findMethodCalls(instrumented, "add", "(II)I",
                "ai/jacc/durableThreads/ReplayState");

        assertTrue(calledMethods.contains("isReplayThread"),
                "Instrumented method should call ReplayState.isReplayThread()");
    }

    @Test
    void methodWithInvokesHasResumeStubs() throws Exception {
        byte[] original = loadClassBytes(SampleTarget.class);
        byte[] instrumented = instrument(original);

        // callsMultipleMethods has several invoke instructions
        // The instrumented version should call isReplayThread, currentResumeIndex,
        // isLastFrame, etc.
        List<String> calledMethods = findMethodCalls(instrumented,
                "callsMultipleMethods", "()V",
                "ai/jacc/durableThreads/ReplayState");

        assertTrue(calledMethods.contains("isReplayThread"));
        assertTrue(calledMethods.contains("currentResumeIndex"),
                "Method with invokes should use currentResumeIndex for dispatch");
        assertTrue(calledMethods.contains("isLastFrame"),
                "Resume stubs should check isLastFrame");
    }

    @Test
    void methodWithNoInvokesStillGetsReplayCheck() throws Exception {
        byte[] original = loadClassBytes(SampleTarget.class);
        byte[] instrumented = instrument(original);

        // add(int, int) has no invoke instructions (just IADD and IRETURN)
        List<String> calledMethods = findMethodCalls(instrumented, "add", "(II)I",
                "ai/jacc/durableThreads/ReplayState");

        assertTrue(calledMethods.contains("isReplayThread"),
                "Even methods without invokes get the replay check");
    }

    @Test
    void staticMethodIsInstrumented() throws Exception {
        byte[] original = loadClassBytes(SampleTarget.class);
        byte[] instrumented = instrument(original);

        List<String> calledMethods = findMethodCalls(instrumented,
                "staticMethod", "(I)V",
                "ai/jacc/durableThreads/ReplayState");

        assertTrue(calledMethods.contains("isReplayThread"));
    }

    @Test
    void instrumentedMethodPreservesOriginalInstructions() throws Exception {
        byte[] original = loadClassBytes(SampleTarget.class);
        byte[] instrumented = instrument(original);

        // The original System.out.println call should still be present
        List<String> allCalls = findAllMethodCalls(instrumented,
                "callsMultipleMethods", "()V");

        // Should contain original calls: length, toUpperCase, println
        // Plus prologue calls: isReplayThread, etc.
        assertTrue(allCalls.stream().anyMatch(s -> s.contains("length")),
                "Original String.length() call should be preserved");
        assertTrue(allCalls.stream().anyMatch(s -> s.contains("toUpperCase")),
                "Original String.toUpperCase() call should be preserved");
        assertTrue(allCalls.stream().anyMatch(s -> s.contains("println")),
                "Original System.out.println() call should be preserved");
    }

    @Test
    void constructorIsSkipped() throws Exception {
        byte[] original = loadClassBytes(SampleTarget.class);
        byte[] instrumented = instrument(original);

        // Constructors are currently skipped because uninitializedThis
        // semantics are incompatible with the skip-check pattern.
        List<String> calledMethods = findMethodCalls(instrumented, "<init>", "()V",
                "ai/jacc/durableThreads/ReplayState");

        assertTrue(calledMethods.isEmpty(),
                "Constructors should not be instrumented (uninitializedThis issue)");
    }

    // --- Helpers ---

    private static byte[] instrument(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        // Use a ClassWriter that can resolve common superclasses via the classloader
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return PrologueInjectorTest.class.getClassLoader();
            }
        };
        PrologueInjector injector = new PrologueInjector(cw);
        cr.accept(injector, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    /**
     * Find all method calls to a specific owner class within a method.
     */
    private static List<String> findMethodCalls(byte[] classBytes,
                                                 String methodName, String methodDesc,
                                                 String targetOwner) {
        List<String> calls = new ArrayList<>();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (name.equals(methodName) && descriptor.equals(methodDesc)) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            if (owner.equals(targetOwner)) {
                                calls.add(name);
                            }
                        }
                    };
                }
                return null;
            }
        }, 0);
        return calls;
    }

    /**
     * Find all method calls within a method (any owner).
     */
    private static List<String> findAllMethodCalls(byte[] classBytes,
                                                    String methodName, String methodDesc) {
        List<String> calls = new ArrayList<>();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (name.equals(methodName) && descriptor.equals(methodDesc)) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            calls.add(owner + "." + name);
                        }
                    };
                }
                return null;
            }
        }, 0);
        return calls;
    }

    private static byte[] loadClassBytes(Class<?> clazz) throws IOException {
        String resourcePath = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is);
            return is.readAllBytes();
        }
    }
}
