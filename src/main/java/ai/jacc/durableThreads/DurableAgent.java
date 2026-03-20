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
     * Called by the JVM when the agent is loaded at startup via -javaagent.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        inst.addTransformer(new DurableTransformer());
        loaded = true;
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
     * If the port was auto-assigned (address=...0), the Attach API is used
     * to resolve the actual port during premain.
     */
    public static int getCachedJdwpPort() {
        return cachedJdwpPort;
    }

    /**
     * Eagerly detect and cache the JDWP port at startup. This avoids
     * repeated Attach API calls during freeze/restore and provides
     * an early, clear error if JDWP isn't configured.
     */
    private static void eagerlyDetectJdwpPort() {
        try {
            int port = ai.jacc.durableThreads.internal.JdiHelper.detectJdwpPort();
            if (port > 0) {
                cachedJdwpPort = port;
            }
        } catch (Exception ignored) {
            // Detection will be retried lazily at freeze/restore time
        }
    }
}
