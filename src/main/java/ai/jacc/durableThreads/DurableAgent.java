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
     * Name of the SimpleJavaTemplates agent class. Looked up by FQCN so that
     * auto-chaining does not depend on jar filename or version.
     */
    private static final String SIMPLE_JAVA_TEMPLATES_AGENT_CLASS =
            "ai.jacc.simplejavatemplates.agent.SimpleJavaTemplatesAgent";

    /** FQCN of SJT's transformer — used for back-compat "already loaded" detection. */
    private static final String SIMPLE_JAVA_TEMPLATES_TRANSFORMER_CLASS =
            "ai.jacc.simplejavatemplates.agent.CallerLocalVariableTransformer";

    /**
     * Called by the JVM when the agent is loaded at startup via -javaagent.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        // If SimpleJavaTemplates is on the classpath, chain its agent BEFORE
        // we register our own transformer so that SJT's transformer runs
        // first on every subsequent class load (the only order in which the
        // two libraries currently interoperate correctly end-to-end). If
        // SJT is absent, this is a no-op.
        maybeChainSimpleJavaTemplatesAgent(inst);

        instrumentation = inst;
        inst.addTransformer(new DurableTransformer());
        loaded = true;

        // Set nonce before port detection so discovery can verify it
        jdwpDiscoveryNonce = java.util.UUID.randomUUID().toString();
        eagerlyDetectJdwpPort();
    }

    /**
     * If the SimpleJavaTemplates agent class is available on the classpath
     * (and not already loaded), invoke its {@code premain} so that its
     * transformer is registered before ours. This means users who only
     * specify {@code -javaagent:durable-threads.jar} but have the
     * SimpleJavaTemplates jar on their application classpath automatically
     * get both features, in the correct order, with no second
     * {@code -javaagent} flag required.
     *
     * <p>Detection is by fully-qualified class name, so any build of
     * SimpleJavaTemplates that exposes the standard agent class will be
     * auto-chained regardless of jar filename or version.</p>
     *
     * <p>Double-registration is avoided two ways:</p>
     * <ul>
     *   <li>Preferred: read SJT's own {@code loaded} flag reflectively. Any
     *       SJT version that has the flag gets idempotent auto-chaining.</li>
     *   <li>Back-compat: check whether SJT's transformer's
     *       {@code annotatedMethods} set is non-empty — a side effect of its
     *       premain eagerly loading {@code Template}. This lets us interop
     *       with older SJT jars that predate the flag.</li>
     * </ul>
     */
    private static void maybeChainSimpleJavaTemplatesAgent(Instrumentation inst) {
        Class<?> agentClass;
        try {
            agentClass = Class.forName(SIMPLE_JAVA_TEMPLATES_AGENT_CLASS);
        } catch (ClassNotFoundException cnfe) {
            return; // SJT not on classpath — nothing to chain.
        } catch (Throwable t) {
            // Any other load failure (LinkageError, NoClassDefFoundError on a
            // transitive dep, etc.) — log and move on. DJT must still function.
            System.err.println("[DurableThreads] SimpleJavaTemplates detected on classpath "
                    + "but its agent class could not be loaded; continuing without "
                    + "auto-chain. Cause: " + t);
            return;
        }

        if (isSimpleJavaTemplatesAlreadyLoaded(agentClass)) {
            return;
        }

        try {
            java.lang.reflect.Method premain = agentClass.getMethod(
                    "premain", String.class, Instrumentation.class);
            premain.invoke(null, null, inst);
            System.err.println("[DurableThreads] Auto-chained SimpleJavaTemplates agent; "
                    + "no additional -javaagent flag required.");
        } catch (Throwable t) {
            System.err.println("[DurableThreads] Failed to auto-chain SimpleJavaTemplates "
                    + "agent. Load it explicitly with -javaagent:SimpleJavaTemplates.jar "
                    + "BEFORE the durable-threads -javaagent. Cause: " + t);
        }
    }

    private static boolean isSimpleJavaTemplatesAlreadyLoaded(Class<?> agentClass) {
        // Preferred path: read SimpleJavaTemplatesAgent.loaded (present in
        // SJT versions that know about auto-chain).
        try {
            java.lang.reflect.Field loadedField = agentClass.getDeclaredField("loaded");
            loadedField.setAccessible(true);
            Object v = loadedField.get(null);
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
        } catch (NoSuchFieldException nsfe) {
            // Older SJT version — fall through to back-compat detection.
        } catch (Throwable ignored) {
            // Reflection glitch — err on the side of not double-registering,
            // since a duplicate register is worse than a missed auto-chain.
            return true;
        }

        // Back-compat path: SJT's premain eagerly loads Template, which
        // triggers its transformer to populate annotatedMethods. If the set
        // is non-empty, the premain has already run.
        try {
            Class<?> tCls = Class.forName(SIMPLE_JAVA_TEMPLATES_TRANSFORMER_CLASS);
            java.lang.reflect.Field f = tCls.getDeclaredField("annotatedMethods");
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof java.util.Collection<?>) {
                return !((java.util.Collection<?>) v).isEmpty();
            }
        } catch (Throwable ignored) {
            // If we can't detect, err on the side of not double-registering.
            return true;
        }
        return false;
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
