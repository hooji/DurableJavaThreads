package ai.jacc.durableThreads.snapshot;

import java.io.Serializable;
import java.util.Map;

/**
 * Snapshot of a single object on the heap.
 *
 * @param id                  unique ID within the snapshot
 * @param className           fully qualified class name
 * @param kind                object classification (REGULAR, ARRAY, STRING)
 * @param fields              field name → value mappings (key format: "declaring.Class.fieldName")
 * @param arrayElements       array element values (null for non-arrays)
 * @param classStructureHash  SHA-256 hash of the class's field layout (null for arrays/strings)
 */
public record ObjectSnapshot(
        long id,
        String className,
        ObjectKind kind,
        Map<String, ObjectRef> fields,
        ObjectRef[] arrayElements,
        byte[] classStructureHash
) implements Serializable {

    /**
     * Backwards-compatible constructor without classStructureHash.
     */
    public ObjectSnapshot(long id, String className, ObjectKind kind,
                          Map<String, ObjectRef> fields, ObjectRef[] arrayElements) {
        this(id, className, kind, fields, arrayElements, null);
    }
}
