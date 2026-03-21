package ai.jacc.durableThreads.internal;

import com.sun.jdi.*;
import ai.jacc.durableThreads.snapshot.*;

import java.util.*;

/**
 * Walks the object graph via JDI (Java Debug Interface) to capture
 * objects reachable from local variables in a suspended thread.
 *
 * <p>Unlike {@link HeapWalker} which operates on live Java objects in the
 * same JVM, this walker reads object state through JDI mirrors, which
 * works even when the target thread is suspended.</p>
 *
 * <p>This handles objects that don't implement Serializable — we extract
 * field data directly via JDI reflection, bypassing Java serialization.</p>
 */
public final class JdiHeapWalker {

    private long nextId = 1;
    private final Map<Long, Long> jdiIdToSnapId = new HashMap<>();
    private final List<ObjectSnapshot> snapshots = new ArrayList<>();
    private final Map<Long, byte[]> classStructureHashes = new HashMap<>();

    /**
     * Capture a JDI Value into the snapshot heap.
     *
     * @param value the JDI Value (may be null)
     * @return an ObjectRef pointing into the snapshot
     */
    public ObjectRef capture(Value value) {
        if (value == null) {
            return new NullRef();
        }

        if (value instanceof PrimitiveValue) {
            return capturePrimitive((PrimitiveValue) value);
        }

        if (value instanceof StringReference) {
            return new PrimitiveRef(((StringReference) value).value());
        }

        if (value instanceof ObjectReference) {
            return captureObject((ObjectReference) value);
        }

        return new NullRef();
    }

    /**
     * Get all captured object snapshots.
     */
    public List<ObjectSnapshot> getSnapshots() {
        return Collections.unmodifiableList(snapshots);
    }

    /**
     * Get the class structure hash for a given snapshot object ID.
     */
    public byte[] getClassStructureHash(long snapId) {
        return classStructureHashes.get(snapId);
    }

    private ObjectRef capturePrimitive(PrimitiveValue pv) {
        if (pv instanceof BooleanValue) return new PrimitiveRef(((BooleanValue) pv).value());
        if (pv instanceof ByteValue) return new PrimitiveRef(((ByteValue) pv).value());
        if (pv instanceof CharValue) return new PrimitiveRef(((CharValue) pv).value());
        if (pv instanceof ShortValue) return new PrimitiveRef(((ShortValue) pv).value());
        if (pv instanceof IntegerValue) return new PrimitiveRef(((IntegerValue) pv).value());
        if (pv instanceof LongValue) return new PrimitiveRef(((LongValue) pv).value());
        if (pv instanceof FloatValue) return new PrimitiveRef(((FloatValue) pv).value());
        if (pv instanceof DoubleValue) return new PrimitiveRef(((DoubleValue) pv).value());
        return new PrimitiveRef(0);
    }

    private ObjectRef captureObject(ObjectReference objRef) {
        long jdiId = objRef.uniqueID();

        // Already visited — return existing reference
        Long existingSnapId = jdiIdToSnapId.get(jdiId);
        if (existingSnapId != null) {
            return new HeapRef(existingSnapId);
        }

        long snapId = nextId++;
        jdiIdToSnapId.put(jdiId, snapId);

        ReferenceType refType = objRef.referenceType();
        String className = refType.name();

        if (objRef instanceof StringReference) {
            captureString(snapId, (StringReference) objRef);
        } else if (objRef instanceof ArrayReference) {
            // Convert JDI display name ("int[]", "java.lang.String[][]") to
            // JVM internal name ("[I", "[[Ljava.lang.String;") for Class.forName()
            String jvmArrayName = toJvmArrayName(className);
            captureArray(snapId, (ArrayReference) objRef, jvmArrayName);
        } else {
            captureRegularObject(snapId, objRef, refType, className);
        }

        return new HeapRef(snapId);
    }

    private void captureString(long snapId, StringReference sr) {
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef(sr.value()));
        snapshots.add(new ObjectSnapshot(snapId, "java.lang.String",
                ObjectKind.STRING, fields, null, null));
    }

    private void captureArray(long snapId, ArrayReference arrRef, String className) {
        List<Value> values = arrRef.getValues();
        ObjectRef[] elements = new ObjectRef[values.size()];

        for (int i = 0; i < values.size(); i++) {
            elements[i] = capture(values.get(i));
        }

        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.ARRAY, Collections.<String, ObjectRef>emptyMap(), elements, null));
    }

    /** Packages whose internal fields should not be walked (JDK internals). */
    private static final String[] OPAQUE_PACKAGES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun."
    };

    private static boolean isOpaqueType(String className) {
        for (String prefix : OPAQUE_PACKAGES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Collection types we can capture by walking internals via JDI. */
    private static final Set<String> CAPTURABLE_COLLECTIONS = new HashSet<>(Arrays.asList(
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.ArrayDeque"
    ));

    /** JDK types whose content can be captured via toString(). */
    private static final Set<String> TOSTRING_CAPTURABLE = new HashSet<>(Arrays.asList(
            "java.lang.StringBuilder", "java.lang.StringBuffer"
    ));

    private void captureRegularObject(long snapId, ObjectReference objRef,
                                       ReferenceType refType, String className) {
        // Known JDK collections: capture elements by walking internal storage
        if (CAPTURABLE_COLLECTIONS.contains(className)) {
            captureCollection(snapId, objRef, refType, className);
            return;
        }

        // StringBuilder/StringBuffer: capture content via toString()
        if (TOSTRING_CAPTURABLE.contains(className)) {
            captureStringBuilder(snapId, objRef, refType, className);
            return;
        }

        // Other JDK internal types: store as opaque (inaccessible fields)
        if (isOpaqueType(className)) {
            snapshots.add(new ObjectSnapshot(snapId, className,
                    ObjectKind.REGULAR, Collections.<String, ObjectRef>emptyMap(), null, null));
            return;
        }

        Map<String, ObjectRef> fields = new LinkedHashMap<>();

        // Walk class hierarchy via JDI
        ReferenceType current = refType;
        Set<String> visitedTypes = new HashSet<>();

        while (current != null) {
            String typeName = current.name();
            if (typeName.equals("java.lang.Object") || visitedTypes.contains(typeName)) break;
            visitedTypes.add(typeName);

            List<Field> jdiFields = current.fields();
            // Get all field values in one call (more efficient than per-field)
            Map<Field, Value> fieldValues = objRef.getValues(jdiFields);

            for (Field jdiField : jdiFields) {
                if (jdiField.isStatic()) continue;
                // Skip transient fields
                if (isTransient(jdiField)) continue;

                String fieldKey = typeName + "." + jdiField.name();
                Value value = fieldValues.get(jdiField);
                fields.put(fieldKey, capture(value));
            }

            // Walk to superclass
            if (current instanceof ClassType) {
                current = ((ClassType) current).superclass();
            } else {
                break;
            }
        }

        // Compute class structure hash
        byte[] structureHash = ClassStructureHasher.hashClassStructure(refType);
        classStructureHashes.put(snapId, structureHash);

        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.REGULAR, fields, null, structureHash));
    }

    /**
     * Capture a StringBuilder/StringBuffer by invoking toString() via JDI.
     * Stored as a STRING kind with the actual class name so HeapRestorer
     * can reconstruct it using the String constructor.
     */
    private void captureStringBuilder(long snapId, ObjectReference objRef,
                                       ReferenceType refType, String className) {
        String content = "";
        try {
            Method toStringMethod = refType.methodsByName("toString", "()Ljava/lang/String;").get(0);
            // We need to invoke toString() on the suspended thread — but the object
            // is already fully formed, so we can read its internal state directly.
            // Instead of invoking methods (which requires a running thread), read
            // the count and value fields via JDI field access.
            com.sun.jdi.Field countField = findField(refType, "count");
            com.sun.jdi.Field valueField = findField(refType, "value");
            com.sun.jdi.Field coderField = findField(refType, "coder");

            if (valueField != null && countField != null) {
                Value countVal = objRef.getValue(countField);
                Value coderVal = coderField != null ? objRef.getValue(coderField) : null;
                Value valueVal = objRef.getValue(valueField);
                int count = (countVal instanceof IntegerValue) ? ((IntegerValue) countVal).value() : 0;
                int coder = (coderVal instanceof IntegerValue) ? ((IntegerValue) coderVal).value() : 0;

                if (valueVal instanceof ArrayReference && count > 0) {
                    ArrayReference arr = (ArrayReference) valueVal;
                    List<Value> vals = arr.getValues(0, Math.min(count, arr.length()));
                    if (coderField == null || vals.get(0) instanceof CharValue) {
                        // Java 8: value is a char[] array
                        char[] chars = new char[count];
                        for (int i = 0; i < count; i++) {
                            chars[i] = (vals.get(i) instanceof CharValue) ? ((CharValue) vals.get(i)).value() : 0;
                        }
                        content = new String(chars);
                    } else if (coder == 0) {
                        // Java 9+ LATIN1: each byte is a char
                        byte[] bytes = new byte[count];
                        for (int i = 0; i < count; i++) {
                            bytes[i] = (vals.get(i) instanceof ByteValue) ? ((ByteValue) vals.get(i)).value() : 0;
                        }
                        content = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                    } else {
                        // Java 9+ UTF16: two bytes per char
                        byte[] bytes = new byte[count * 2];
                        List<Value> utf16Vals = arr.getValues(0, count * 2);
                        for (int i = 0; i < count * 2; i++) {
                            bytes[i] = (utf16Vals.get(i) instanceof ByteValue) ? ((ByteValue) utf16Vals.get(i)).value() : 0;
                        }
                        content = new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    "Cannot read " + className + " content via JDI. "
                    + "Thread freeze cannot proceed with incomplete state.", e);
        }

        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef(content));
        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.STRING, fields, null, null));
    }

    /**
     * Capture a JDK collection by walking its internal storage via JDI.
     * For List/Set: elements stored in arrayElements.
     * For Map: key/value pairs interleaved in arrayElements (k0,v0,k1,v1,...).
     */
    private void captureCollection(long snapId, ObjectReference objRef,
                                    ReferenceType refType, String className) {
        if (className.contains("Map")) {
            captureMap(snapId, objRef, refType, className);
        } else {
            captureListOrSet(snapId, objRef, refType, className);
        }
    }

    private void captureListOrSet(long snapId, ObjectReference objRef,
                                   ReferenceType refType, String className) {
        List<ObjectRef> elements = new ArrayList<>();

        // Try to read size and elementData (ArrayList) or navigate linked structure
        com.sun.jdi.Field sizeField = findField(refType, "size");
        com.sun.jdi.Field elementDataField = findField(refType, "elementData");

        if (elementDataField != null && sizeField != null) {
            // ArrayList-like: read elementData array up to size
            Value sizeVal = objRef.getValue(sizeField);
            int size = (sizeVal instanceof IntegerValue) ? ((IntegerValue) sizeVal).value() : 0;
            Value dataVal = objRef.getValue(elementDataField);
            if (dataVal instanceof ArrayReference) {
                ArrayReference arr = (ArrayReference) dataVal;
                for (int i = 0; i < Math.min(size, arr.length()); i++) {
                    elements.add(capture(arr.getValue(i)));
                }
            }
        } else {
            // HashSet wraps a HashMap — read the map's keys
            com.sun.jdi.Field mapField = findField(refType, "map");
            if (mapField != null) {
                Value mapVal = objRef.getValue(mapField);
                if (mapVal instanceof ObjectReference) {
                    List<ObjectRef> pairs = extractMapEntries((ObjectReference) mapVal);
                    // HashSet: only keep keys (even indices)
                    for (int i = 0; i < pairs.size(); i += 2) {
                        elements.add(pairs.get(i));
                    }
                }
            }
        }

        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.COLLECTION, Collections.<String, ObjectRef>emptyMap(), elements.toArray(new ObjectRef[0]), null));
    }

    private void captureMap(long snapId, ObjectReference objRef,
                             ReferenceType refType, String className) {
        List<ObjectRef> pairs = extractMapEntries(objRef);
        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.COLLECTION, Collections.<String, ObjectRef>emptyMap(), pairs.toArray(new ObjectRef[0]), null));
    }

    /** Extract key/value pairs from a HashMap-like structure via JDI. */
    private List<ObjectRef> extractMapEntries(ObjectReference mapRef) {
        List<ObjectRef> pairs = new ArrayList<>();
        try {
            ReferenceType mapType = mapRef.referenceType();
            com.sun.jdi.Field tableField = findField(mapType, "table");
            if (tableField == null) return pairs;

            Value tableVal = mapRef.getValue(tableField);
            if (!(tableVal instanceof ArrayReference)) return pairs;
            ArrayReference table = (ArrayReference) tableVal;

            for (int i = 0; i < table.length(); i++) {
                Value bucketVal = table.getValue(i);
                if (!(bucketVal instanceof ObjectReference)) continue;
                ObjectReference node = (ObjectReference) bucketVal;

                // Walk the linked list in each bucket
                while (node != null) {
                    com.sun.jdi.Field keyField = findField(node.referenceType(), "key");
                    com.sun.jdi.Field valField = findField(node.referenceType(), "val");
                    if (valField == null) valField = findField(node.referenceType(), "value");

                    if (keyField != null && valField != null) {
                        pairs.add(capture(node.getValue(keyField)));
                        pairs.add(capture(node.getValue(valField)));
                    }

                    com.sun.jdi.Field nextField = findField(node.referenceType(), "next");
                    if (nextField != null) {
                        Value nextVal = node.getValue(nextField);
                        node = (nextVal instanceof ObjectReference) ? (ObjectReference) nextVal : null;
                    } else {
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract map entries via JDI", e);
        }
        return pairs;
    }

    private static com.sun.jdi.Field findField(ReferenceType type, String name) {
        com.sun.jdi.Field f = type.fieldByName(name);
        if (f != null) return f;
        if (type instanceof ClassType && ((ClassType) type).superclass() != null) {
            return findField(((ClassType) type).superclass(), name);
        }
        return null;
    }

    private static boolean isTransient(Field field) {
        // JDI Field doesn't have isTransient() directly, check modifiers
        return (field.modifiers() & 0x0080) != 0; // ACC_TRANSIENT = 0x0080
    }

    /**
     * Convert JDI array type display name to JVM internal name.
     * JDI gives "int[]", "byte[][]", "java.lang.String[]" etc.
     * Class.forName needs "[I", "[[B", "[Ljava.lang.String;" etc.
     */
    static String toJvmArrayName(String jdiName) {
        // Count dimensions
        int dims = 0;
        String base = jdiName;
        while (base.endsWith("[]")) {
            dims++;
            base = base.substring(0, base.length() - 2);
        }
        if (dims == 0) return jdiName; // not an array

        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < dims; i++) {
            prefix.append('[');
        }
        String descriptor;
        if ("boolean".equals(base)) descriptor = "Z";
        else if ("byte".equals(base)) descriptor = "B";
        else if ("char".equals(base)) descriptor = "C";
        else if ("short".equals(base)) descriptor = "S";
        else if ("int".equals(base)) descriptor = "I";
        else if ("long".equals(base)) descriptor = "J";
        else if ("float".equals(base)) descriptor = "F";
        else if ("double".equals(base)) descriptor = "D";
        else descriptor = "L" + base + ";";
        return prefix.toString() + descriptor;
    }
}
