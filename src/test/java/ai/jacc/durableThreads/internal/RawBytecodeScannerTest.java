package ai.jacc.durableThreads.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RawBytecodeScannerTest {

    static class SimpleTarget {
        public static int add(int a, int b) { return a + b; }
        public static void callsTwo() {
            System.out.println("hello");
            System.out.println("world");
        }
    }

    @Test
    void scanFindsNoInvokesInPureArithmetic() throws IOException {
        byte[] classBytes = loadClassBytes(SimpleTarget.class);
        List<Integer> offsets = RawBytecodeScanner.scanInvokeOffsets(classBytes, "add", "(II)I");
        assertTrue(offsets.isEmpty(), "add() has no invoke instructions");
    }

    @Test
    void scanFindsTwoInvokesInCallsTwo() throws IOException {
        byte[] classBytes = loadClassBytes(SimpleTarget.class);
        List<Integer> offsets = RawBytecodeScanner.scanInvokeOffsets(classBytes, "callsTwo", "()V");
        assertEquals(2, offsets.size(), "callsTwo() has 2 println calls. Found: " + offsets);
        assertTrue(offsets.get(0) < offsets.get(1), "Offsets should be in order");
    }

    @Test
    void scanWorksOnFreezeProgram() throws IOException {
        byte[] classBytes = loadClassBytes(
                ai.jacc.durableThreads.e2e.FreezeProgram.class);
        // FreezeProgram.main has several invokes
        List<Integer> offsets = RawBytecodeScanner.scanInvokeOffsets(
                classBytes, "main", "([Ljava/lang/String;)V");
        System.out.println("FreezeProgram.main invokes: " + offsets);
        // main calls: args.length check, new Thread, worker.start, worker.join...
        assertFalse(offsets.isEmpty(), "FreezeProgram.main should have invokes");
    }

    @Test
    void scanWorksOnInstrumentedBytecode() throws IOException {
        // Instrument SimpleTarget and scan the result
        byte[] original = loadClassBytes(SimpleTarget.class);
        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(original);
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return RawBytecodeScannerTest.class.getClassLoader();
            }
        };
        ai.jacc.durableThreads.PrologueInjector injector =
                new ai.jacc.durableThreads.PrologueInjector(cw);
        cr.accept(injector, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        byte[] instrumented = cw.toByteArray();

        List<Integer> offsets = RawBytecodeScanner.scanInvokeOffsets(
                instrumented, "callsTwo", "()V");
        System.out.println("Instrumented callsTwo invokes: " + offsets);
        // The instrumented code has 4 println calls: 2 in resume stubs (re-invoke for
        // stack replay) + 2 in the original code section. ReplayState calls are filtered.
        assertEquals(4, offsets.size(),
                "Instrumented callsTwo should have 4 user invokes (2 stub + 2 original). Found: " + offsets);
    }

    private static byte[] loadClassBytes(Class<?> clazz) throws IOException {
        String resourcePath = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Could not load class bytes for " + clazz.getName());
            return is.readAllBytes();
        }
    }
}
