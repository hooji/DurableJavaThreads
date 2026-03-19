package com.u1.durableThreads;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point. Registers the {@link DurableTransformer} that injects
 * the replay prologue into every loaded class.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * # Instrument everything (except JDK and library internals):
 * -javaagent:durable-threads.jar
 *
 * # Only instrument specific packages:
 * -javaagent:durable-threads.jar=includes=com.myapp;com.mylib
 *
 * # Exclude specific packages:
 * -javaagent:durable-threads.jar=excludes=com.thirdparty;org.logging
 *
 * # Both (includes take precedence — only included packages are instrumented,
 * # minus any excluded sub-packages):
 * -javaagent:durable-threads.jar=includes=com.myapp&excludes=com.myapp.generated
 * }</pre>
 *
 * <p>Package names use dots (e.g. {@code com.myapp}). Multiple packages are
 * separated by semicolons. The agent always excludes JDK internals and its own
 * classes regardless of user configuration.</p>
 */
public final class DurableAgent {

    private static volatile boolean loaded = false;
    private static volatile Instrumentation instrumentation;

    /**
     * Called by the JVM when the agent is loaded at startup via -javaagent.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        InstrumentationScope scope = InstrumentationScope.parse(agentArgs);
        inst.addTransformer(new DurableTransformer(scope));
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
