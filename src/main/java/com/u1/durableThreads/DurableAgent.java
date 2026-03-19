package com.u1.durableThreads;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point. Registers the {@link DurableTransformer} that injects
 * the replay prologue into every loaded class.
 *
 * <p>Usage: {@code -javaagent:durable-threads.jar}</p>
 */
public final class DurableAgent {

    private static volatile boolean loaded = false;
    private static volatile Instrumentation instrumentation;

    /**
     * Called by the JVM when the agent is loaded at startup via -javaagent.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        inst.addTransformer(new DurableTransformer());
        loaded = true;
    }

    /**
     * Check if the durable agent has been loaded.
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * Get the Instrumentation instance (available after agent load).
     */
    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}
