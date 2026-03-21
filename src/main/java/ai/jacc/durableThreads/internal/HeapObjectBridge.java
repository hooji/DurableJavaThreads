package ai.jacc.durableThreads.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge for passing restored heap objects to JDI.
 *
 * <p>JDI cannot directly create ObjectReferences from local Java objects
 * in the same JVM. This bridge stores objects by key so that JDI can
 * read them via field access on the target VM's copy of this class.</p>
 *
 * <p>The JDI worker stores restored objects here, then reads them back
 * through JDI's ReferenceType.getValue() to obtain ObjectReferences
 * that can be set as local variables in the target thread's frames.</p>
 */
public final class HeapObjectBridge {

    private HeapObjectBridge() {}

    /**
     * Static map accessible via JDI. Keys are snapshot object IDs (as strings),
     * values are the restored Java objects.
     */
    public static final Map<String, Object> objects = new ConcurrentHashMap<>();

    /**
     * Store a restored object for JDI access.
     */
    public static void put(long snapshotId, Object obj) {
        if (obj != null) {
            objects.put(String.valueOf(snapshotId), obj);
        }
    }

    /**
     * Retrieve a stored object by snapshot ID.
     */
    public static Object get(long snapshotId) {
        return objects.get(String.valueOf(snapshotId));
    }

    /**
     * Clear all stored objects (call after restore completes).
     */
    public static void clear() {
        objects.clear();
    }
}
