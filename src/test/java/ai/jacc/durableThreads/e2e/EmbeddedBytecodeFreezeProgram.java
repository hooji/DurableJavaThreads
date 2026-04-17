package ai.jacc.durableThreads.e2e;

import ai.jacc.durableThreads.Durable;

/**
 * Wrapper for {@link HeapObjectFreezeProgram} that enables class-byte
 * bundling before delegating. Exists so the {@code EmbeddedBytecodeIT}
 * E2E test can exercise the opt-in bundling path without altering the
 * default-behavior freeze programs used by every other test.
 */
public class EmbeddedBytecodeFreezeProgram {

    public static void main(String[] args) throws Exception {
        Durable.setEmbedClassBytecodes(true);
        HeapObjectFreezeProgram.main(args);
    }
}
