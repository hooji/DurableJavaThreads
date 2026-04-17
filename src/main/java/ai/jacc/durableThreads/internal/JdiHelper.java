package ai.jacc.durableThreads.internal;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

/**
 * Small JDI navigation helpers used by the freeze/restore machinery.
 *
 * <p>Port discovery and self-attach live in {@link PortEnumerator},
 * {@link PortProber}, and {@link SelfConnection}.</p>
 */
public final class JdiHelper {

    private JdiHelper() {}

    /**
     * Find the JDI ThreadReference corresponding to a Java thread.
     *
     * <p><b>Important:</b> We match by thread name, NOT by
     * {@link ObjectReference#uniqueID()}. JDI's {@code uniqueID()} is an
     * internal mirror-object identifier that has <em>no</em> relation to
     * {@link Thread#threadId()}. Comparing the two can cause accidental
     * collisions (returning the wrong thread), leading to corrupt snapshots
     * with zero user frames.</p>
     */
    public static ThreadReference findThread(VirtualMachine vm, Thread javaThread) {
        String name = javaThread.getName();
        for (ThreadReference tr : vm.allThreads()) {
            if (tr.name().equals(name)) {
                return tr;
            }
        }
        throw new RuntimeException("Could not find JDI ThreadReference for thread: " + name);
    }

    /**
     * Find a field by name in a JDI type or its supertypes.
     *
     * <p>ConcurrentHashMap's internal {@code Node} class inherits fields from
     * superclasses, so a simple {@code fieldByName} on the concrete type may
     * miss them. This walks up the class hierarchy.</p>
     */
    public static com.sun.jdi.Field findFieldInHierarchy(ReferenceType type, String name) {
        com.sun.jdi.Field f = type.fieldByName(name);
        if (f != null) return f;
        if (type instanceof ClassType && ((ClassType) type).superclass() != null) {
            return findFieldInHierarchy(((ClassType) type).superclass(), name);
        }
        return null;
    }

    /**
     * Look up a single value by String key in a {@code ConcurrentHashMap}
     * accessed via JDI.
     *
     * <p>Walks the internal {@code table} array and node chains to find the
     * entry matching {@code targetKey}. Depends on ConcurrentHashMap's
     * internal structure ({@code table}, {@code key}, {@code val},
     * {@code next}), which has been stable across JDK 8–25.</p>
     *
     * @param mapRef    JDI reference to the ConcurrentHashMap instance
     * @param targetKey the String key to search for
     * @return the Value associated with the key, or null if not found
     */
    public static Value getConcurrentHashMapValue(ObjectReference mapRef, String targetKey) {
        ReferenceType mapType = mapRef.referenceType();

        com.sun.jdi.Field tableField = findFieldInHierarchy(mapType, "table");
        if (tableField == null) return null;

        ArrayReference table = (ArrayReference) mapRef.getValue(tableField);
        if (table == null) return null;

        for (int i = 0; i < table.length(); i++) {
            ObjectReference node = (ObjectReference) table.getValue(i);
            while (node != null) {
                com.sun.jdi.Field keyField = findFieldInHierarchy(node.referenceType(), "key");
                com.sun.jdi.Field valField = findFieldInHierarchy(node.referenceType(), "val");
                com.sun.jdi.Field nextField = findFieldInHierarchy(node.referenceType(), "next");

                if (keyField == null || valField == null) break;

                Value keyVal = node.getValue(keyField);
                if (keyVal instanceof StringReference
                        && ((StringReference) keyVal).value().equals(targetKey)) {
                    return node.getValue(valField);
                }

                if (nextField != null) {
                    Value nextVal = node.getValue(nextField);
                    node = (nextVal instanceof ObjectReference) ? (ObjectReference) nextVal : null;
                } else {
                    break;
                }
            }
        }
        return null;
    }
}
