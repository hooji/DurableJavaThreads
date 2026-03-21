package ai.jacc.durableThreads.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping (class, method) to the bytecode offsets of invoke instructions
 * in the instrumented bytecode. Used at freeze time to convert JDI-reported bytecode
 * positions into invoke indices for the replay prologue's tableswitch.
 */
public final class InvokeRegistry {

    /**
     * Key: "className#methodName+methodDescriptor"
     * Value: ordered list of invoke instruction bytecode offsets in the instrumented method
     */
    private static final Map<String, List<Integer>> INVOKE_OFFSETS = new ConcurrentHashMap<>();

    /**
     * Instrumented bytecode for each class, keyed by internal class name.
     * Used for bytecode hash computation.
     */
    private static final Map<String, byte[]> INSTRUMENTED_BYTECODE = new ConcurrentHashMap<>();

    private InvokeRegistry() {}

    public static String key(String className, String methodName, String methodDescriptor) {
        return className + "#" + methodName + "+" + methodDescriptor;
    }

    /**
     * Register the invoke offsets for a method after instrumentation.
     */
    public static void register(String key, List<Integer> offsets) {
        INVOKE_OFFSETS.put(key, Collections.unmodifiableList(new ArrayList<>(offsets)));
    }

    /**
     * Get the invoke index for a given bytecode position.
     * Returns the index of the invoke instruction at or nearest before the given BCP.
     *
     * @return the invoke index, or -1 if not found
     */
    public static int getInvokeIndex(String key, long bcp) {
        List<Integer> offsets = INVOKE_OFFSETS.get(key);
        if (offsets == null) return -1;

        // Find the invoke instruction at or just before the given BCP.
        // The BCP from JDI may point to the invoke instruction itself or
        // to a position after it (if the thread was suspended after the call).
        for (int i = offsets.size() - 1; i >= 0; i--) {
            if (offsets.get(i) <= bcp) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Get the bytecode offsets of invoke instructions for a method.
     */
    public static List<Integer> getInvokeOffsets(String key) {
        return INVOKE_OFFSETS.get(key);
    }

    /**
     * Store instrumented bytecode for a class.
     */
    public static void storeInstrumentedBytecode(String className, byte[] bytecode) {
        INSTRUMENTED_BYTECODE.put(className, bytecode);
    }

    /**
     * Get stored instrumented bytecode for a class.
     */
    public static byte[] getInstrumentedBytecode(String className) {
        return INSTRUMENTED_BYTECODE.get(className);
    }

    /**
     * Check if a class has been instrumented.
     */
    public static boolean isInstrumented(String className) {
        return INSTRUMENTED_BYTECODE.containsKey(className);
    }
}
