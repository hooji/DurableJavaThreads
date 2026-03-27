package ai.jacc.durableThreads;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point. Registers the {@link DurableTransformer} that injects
 * the replay prologue into every loaded class.
 *
 * <p>Usage: {@code -javaagent:durable-threads.jar}</p>
 *
 * <p>At startup, the agent eagerly detects and caches the JDWP port so that
 * subsequent freeze/restore operations can connect without delay. If JDWP
 * is not enabled, a clear error is printed.</p>
 */
public final class DurableAgent {

    private static volatile boolean loaded = false;
    private static volatile Instrumentation instrumentation;
    private static volatile int cachedJdwpPort = -1;

    /**
     * Nonce set at premain time, used by JDWP port discovery to verify that
     * a candidate JDWP connection belongs to THIS JVM (not another JVM on
     * the same host). Read via JDI as a static field — no method invocation
     * needed.
     */
    public static volatile String jdwpDiscoveryNonce = "";

    /**
     * Called by the JVM when the agent is loaded at startup via -javaagent.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        inst.addTransformer(new DurableTransformer());
        loaded = true;

        // Set nonce before port detection so discovery can verify it
        jdwpDiscoveryNonce = java.util.UUID.randomUUID().toString();
        eagerlyDetectJdwpPort();
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

    /**
     * Get the JDWP port detected at startup, or -1 if not yet detected.
     */
    public static int getCachedJdwpPort() {
        return cachedJdwpPort;
    }

    /**
     * Cache a successfully detected JDWP port so subsequent calls to
     * {@code detectJdwpPort()} skip expensive port scanning.
     */
    public static void cacheJdwpPort(int port) {
        if (port > 0) {
            cachedJdwpPort = port;
        }
    }

    /**
     * Eagerly detect and cache the JDWP port at startup.
     */
    private static void eagerlyDetectJdwpPort() {
        try {
            int port = ai.jacc.durableThreads.internal.JdiHelper.detectJdwpPortNoDefault();
            if (port > 0) {
                cachedJdwpPort = port;
            }
        } catch (Throwable ignored) {
            // Detection will be retried lazily at freeze/restore time.
            // Catching Throwable covers NoClassDefFoundError if jdk.attach is absent.
        }
    }
}
