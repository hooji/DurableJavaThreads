package com.u1.durableThreads.internal;

import com.sun.jdi.*;
import com.u1.durableThreads.snapshot.*;

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

        if (value instanceof PrimitiveValue pv) {
            return capturePrimitive(pv);
        }

        if (value instanceof StringReference sr) {
            return new PrimitiveRef(sr.value());
        }

        if (value instanceof ObjectReference objRef) {
            return captureObject(objRef);
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
        return switch (pv) {
            case BooleanValue v -> new PrimitiveRef(v.value());
            case ByteValue v -> new PrimitiveRef(v.value());
            case CharValue v -> new PrimitiveRef(v.value());
            case ShortValue v -> new PrimitiveRef(v.value());
            case IntegerValue v -> new PrimitiveRef(v.value());
            case LongValue v -> new PrimitiveRef(v.value());
            case FloatValue v -> new PrimitiveRef(v.value());
            case DoubleValue v -> new PrimitiveRef(v.value());
            default -> new PrimitiveRef(0);
        };
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

        if (objRef instanceof StringReference sr) {
            captureString(snapId, sr);
        } else if (objRef instanceof ArrayReference arrRef) {
            // Convert JDI display name ("int[]", "java.lang.String[][]") to
            // JVM internal name ("[I", "[[Ljava.lang.String;") for Class.forName()
            String jvmArrayName = toJvmArrayName(className);
            captureArray(snapId, arrRef, jvmArrayName);
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
                ObjectKind.ARRAY, Map.of(), elements, null));
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
    private static final Set<String> CAPTURABLE_COLLECTIONS = Set.of(
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
    );

    /** JDK types whose content can be captured via toString(). */
    private static final Set<String> TOSTRING_CAPTURABLE = Set.of(
            "java.lang.StringBuilder", "java.lang.StringBuffer"
    );

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
                    ObjectKind.REGULAR, Map.of(), null, null));
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
            if (current instanceof ClassType ct) {
                current = ct.superclass();
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
                int count = (countVal instanceof IntegerValue iv) ? iv.value() : 0;
                int coder = (coderVal instanceof IntegerValue iv) ? iv.value() : 0;

                if (valueVal instanceof ArrayReference arr && count > 0) {
                    if (coder == 0) {
                        // LATIN1: each byte is a char
                        byte[] bytes = new byte[count];
                        List<Value> vals = arr.getValues(0, count);
                        for (int i = 0; i < count; i++) {
                            bytes[i] = (vals.get(i) instanceof ByteValue bv) ? bv.value() : 0;
                        }
                        content = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                    } else {
                        // UTF16: two bytes per char
                        byte[] bytes = new byte[count * 2];
                        List<Value> vals = arr.getValues(0, count * 2);
                        for (int i = 0; i < count * 2; i++) {
                            bytes[i] = (vals.get(i) instanceof ByteValue bv) ? bv.value() : 0;
                        }
                        content = new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE);
                    }
                }
            }
        } catch (Exception e) {
            // Fall back to empty string if we can't read internals
            System.err.println("[DurableThreads] WARNING: Cannot read StringBuilder content: " + e.getMessage());
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
            int size = (sizeVal instanceof IntegerValue iv) ? iv.value() : 0;
            Value dataVal = objRef.getValue(elementDataField);
            if (dataVal instanceof ArrayReference arr) {
                for (int i = 0; i < Math.min(size, arr.length()); i++) {
                    elements.add(capture(arr.getValue(i)));
                }
            }
        } else {
            // HashSet wraps a HashMap — read the map's keys
            com.sun.jdi.Field mapField = findField(refType, "map");
            if (mapField != null) {
                Value mapVal = objRef.getValue(mapField);
                if (mapVal instanceof ObjectReference mapRef) {
                    List<ObjectRef> pairs = extractMapEntries(mapRef);
                    // HashSet: only keep keys (even indices)
                    for (int i = 0; i < pairs.size(); i += 2) {
                        elements.add(pairs.get(i));
                    }
                }
            }
        }

        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.COLLECTION, Map.of(), elements.toArray(new ObjectRef[0]), null));
    }

    private void captureMap(long snapId, ObjectReference objRef,
                             ReferenceType refType, String className) {
        List<ObjectRef> pairs = extractMapEntries(objRef);
        snapshots.add(new ObjectSnapshot(snapId, className,
                ObjectKind.COLLECTION, Map.of(), pairs.toArray(new ObjectRef[0]), null));
    }

    /** Extract key/value pairs from a HashMap-like structure via JDI. */
    private List<ObjectRef> extractMapEntries(ObjectReference mapRef) {
        List<ObjectRef> pairs = new ArrayList<>();
        try {
            ReferenceType mapType = mapRef.referenceType();
            com.sun.jdi.Field tableField = findField(mapType, "table");
            if (tableField == null) return pairs;

            Value tableVal = mapRef.getValue(tableField);
            if (!(tableVal instanceof ArrayReference table)) return pairs;

            for (int i = 0; i < table.length(); i++) {
                Value bucketVal = table.getValue(i);
                if (!(bucketVal instanceof ObjectReference node)) continue;

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
                        node = (nextVal instanceof ObjectReference or) ? or : null;
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
        if (type instanceof ClassType ct && ct.superclass() != null) {
            return findField(ct.superclass(), name);
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

        String prefix = "[".repeat(dims);
        String descriptor = switch (base) {
            case "boolean" -> "Z";
            case "byte"    -> "B";
            case "char"    -> "C";
            case "short"   -> "S";
            case "int"     -> "I";
            case "long"    -> "J";
            case "float"   -> "F";
            case "double"  -> "D";
            default        -> "L" + base + ";";
        };
        return prefix + descriptor;
    }
}
