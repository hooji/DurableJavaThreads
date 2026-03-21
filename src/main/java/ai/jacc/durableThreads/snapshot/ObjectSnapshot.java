package ai.jacc.durableThreads.snapshot;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of a single object on the heap.
 *
 * @see #id()                  unique ID within the snapshot
 * @see #className()           fully qualified class name
 * @see #kind()                object classification (REGULAR, ARRAY, STRING)
 * @see #fields()              field name to value mappings (key format: "declaring.Class.fieldName")
 * @see #arrayElements()       array element values (null for non-arrays)
 * @see #classStructureHash()  SHA-256 hash of the class's field layout (null for arrays/strings)
 */
public final class ObjectSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long id;
    private final String className;
    private final ObjectKind kind;
    private final Map<String, ObjectRef> fields;
    private final ObjectRef[] arrayElements;
    private final byte[] classStructureHash;

    public ObjectSnapshot(long id, String className, ObjectKind kind,
                          Map<String, ObjectRef> fields, ObjectRef[] arrayElements,
                          byte[] classStructureHash) {
        this.id = id;
        this.className = className;
        this.kind = kind;
        this.fields = fields;
        this.arrayElements = arrayElements;
        this.classStructureHash = classStructureHash;
    }

    /**
     * Backwards-compatible constructor without classStructureHash.
     */
    public ObjectSnapshot(long id, String className, ObjectKind kind,
                          Map<String, ObjectRef> fields, ObjectRef[] arrayElements) {
        this(id, className, kind, fields, arrayElements, null);
    }

    public long id() {
        return id;
    }

    public String className() {
        return className;
    }

    public ObjectKind kind() {
        return kind;
    }

    public Map<String, ObjectRef> fields() {
        return fields;
    }

    public ObjectRef[] arrayElements() {
        return arrayElements;
    }

    public byte[] classStructureHash() {
        return classStructureHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ObjectSnapshot)) return false;
        ObjectSnapshot that = (ObjectSnapshot) o;
        return id == that.id
                && Objects.equals(className, that.className)
                && kind == that.kind
                && Objects.equals(fields, that.fields)
                && Arrays.equals(arrayElements, that.arrayElements)
                && Arrays.equals(classStructureHash, that.classStructureHash);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, className, kind, fields);
        result = 31 * result + Arrays.hashCode(arrayElements);
        result = 31 * result + Arrays.hashCode(classStructureHash);
        return result;
    }

    @Override
    public String toString() {
        return "ObjectSnapshot[id=" + id
                + ", className=" + className
                + ", kind=" + kind
                + ", fields=" + fields
                + ", arrayElements=" + Arrays.toString(arrayElements)
                + ", classStructureHash=" + Arrays.toString(classStructureHash) + "]";
    }
}
