package ai.jacc.durableThreads.internal;

import ai.jacc.durableThreads.snapshot.*;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the captured object graph from a snapshot.
 * Uses Objenesis to instantiate objects without calling constructors.
 */
public final class HeapRestorer {

    private static final ObjenesisStd OBJENESIS = new ObjenesisStd(true);

    private final Map<Long, Object> restored = new HashMap<>();
    private final Map<Long, ObjectSnapshot> snapshotMap = new HashMap<>();

    /**
     * Restore all objects from the snapshot heap.
     *
     * @param heap the list of object snapshots
     * @return mapping from snapshot object ID to restored Java object
     */
    public Map<Long, Object> restoreAll(List<ObjectSnapshot> heap) {
        return restoreAll(heap, null);
    }

    /**
     * Restore all objects from the snapshot heap, substituting named objects
     * with live replacements from the provided map.
     *
     * @param heap the list of object snapshots
     * @param namedReplacements map of name → live object to substitute (may be null)
     * @return mapping from snapshot object ID to restored Java object
     * @throws RuntimeException if namedReplacements contains names not found in the snapshot
     */
    public Map<Long, Object> restoreAll(List<ObjectSnapshot> heap,
                                          Map<String, Object> namedReplacements) {
        // Index snapshots by ID
        for (ObjectSnapshot snap : heap) {
            snapshotMap.put(snap.id(), snap);
        }

        // Build index of named snapshot objects
        Map<String, Long> nameToSnapId = new HashMap<>();
        for (ObjectSnapshot snap : heap) {
            if (snap.name() != null) {
                nameToSnapId.put(snap.name(), snap.id());
            }
        }

        // Validate: any name in namedReplacements must exist in the snapshot
        if (namedReplacements != null) {
            List<String> unknownNames = new java.util.ArrayList<>();
            for (String name : namedReplacements.keySet()) {
                if (!nameToSnapId.containsKey(name)) {
                    unknownNames.add(name);
                }
            }
            if (!unknownNames.isEmpty()) {
                throw new RuntimeException(
                        "Named replacement objects were provided for restore but "
                        + "no objects with these names exist in the snapshot: "
                        + unknownNames);
            }
        }

        // First pass: allocate all objects (without setting fields).
        // For named objects with replacements, use the replacement directly.
        for (ObjectSnapshot snap : heap) {
            if (snap.name() != null && namedReplacements != null
                    && namedReplacements.containsKey(snap.name())) {
                // Use the live replacement object instead of creating a new one
                restored.put(snap.id(), namedReplacements.get(snap.name()));
            } else {
                allocate(snap);
            }
        }

        // Note any named snapshot objects that weren't replaced
        if (!nameToSnapId.isEmpty()) {
            List<String> unreplaced = new java.util.ArrayList<>();
            for (String name : nameToSnapId.keySet()) {
                if (namedReplacements == null || !namedReplacements.containsKey(name)) {
                    unreplaced.add(name);
                }
            }
            if (!unreplaced.isEmpty()) {
                System.out.println("[DurableThreads] NOTE: No replacement objects were provided "
                        + "for the following named objects found in this frozen thread: "
                        + unreplaced);
            }
        }

        // Second pass: set fields and array elements.
        // Skip population for named replacement objects — they already have
        // their correct state and internal references.
        for (ObjectSnapshot snap : heap) {
            if (snap.name() != null && namedReplacements != null
                    && namedReplacements.containsKey(snap.name())) {
                continue; // replacement object — don't modify it
            }
            populate(snap);
        }

        return restored;
    }

    /**
     * Resolve an ObjectRef to a live Java object.
     */
    public Object resolve(ObjectRef ref) {
        if (ref instanceof NullRef) {
            return null;
        } else if (ref instanceof PrimitiveRef) {
            return ((PrimitiveRef) ref).value();
        } else if (ref instanceof HeapRef) {
            return restored.get(((HeapRef) ref).id());
        }
        return null;
    }

    private void allocate(ObjectSnapshot snap) {
        if (restored.containsKey(snap.id())) return;

        Object obj;
        switch (snap.kind()) {
            case STRING: {
                // Strings and StringBuilder/StringBuffer: extract value directly
                ObjectRef valueRef = snap.fields().get("value");
                String content = "";
                if (valueRef instanceof PrimitiveRef) {
                    Object val = ((PrimitiveRef) valueRef).value();
                    if (val instanceof String) {
                        content = (String) val;
                    }
                }
                String cn = snap.className();
                if ("java.lang.StringBuilder".equals(cn)) {
                    obj = new StringBuilder(content);
                } else if ("java.lang.StringBuffer".equals(cn)) {
                    obj = new StringBuffer(content);
                } else {
                    obj = content; // java.lang.String
                }
                break;
            }
            case ARRAY: {
                Class<?> componentType = resolveArrayComponentType(snap.className());
                int length = snap.arrayElements() != null ? snap.arrayElements().length : 0;
                obj = Array.newInstance(componentType, length);
                break;
            }
            case COLLECTION: {
                // Collections are rebuilt by adding elements in the populate pass
                obj = createEmptyCollection(snap.className());
                break;
            }
            case REGULAR: {
                try {
                    Class<?> clazz = Class.forName(snap.className());
                    obj = OBJENESIS.newInstance(clazz);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Cannot restore object of type: " + snap.className(), e);
                }
                break;
            }
            default:
                throw new RuntimeException("Unknown ObjectKind: " + snap.kind());
        }

        restored.put(snap.id(), obj);
    }

    private void populate(ObjectSnapshot snap) {
        Object obj = restored.get(snap.id());
        if (obj == null) return;

        switch (snap.kind()) {
            case STRING:
                // Strings are immutable, already set during allocation
                break;
            case ARRAY:
                populateArray(obj, snap);
                break;
            case COLLECTION:
                populateCollection(obj, snap);
                break;
            case REGULAR:
                populateRegularObject(obj, snap);
                break;
        }
    }

    @SuppressWarnings("unchecked")
    private void populateCollection(Object obj, ObjectSnapshot snap) {
        ObjectRef[] elements = snap.arrayElements();
        if (elements == null || elements.length == 0) return;

        String className = snap.className();

        if (className.contains("Map")) {
            // Map: elements are interleaved key/value pairs
            if (obj instanceof java.util.Map) {
                java.util.Map map = (java.util.Map) obj;
                for (int i = 0; i + 1 < elements.length; i += 2) {
                    Object key = resolve(elements[i]);
                    Object value = resolve(elements[i + 1]);
                    map.put(key, value);
                }
            } else {
                throw new RuntimeException("Expected Map instance for " + className
                        + " but got " + obj.getClass().getName());
            }
        } else {
            // List, Set, Deque: elements are sequential
            if (obj instanceof java.util.Collection) {
                java.util.Collection coll = (java.util.Collection) obj;
                for (ObjectRef element : elements) {
                    coll.add(resolve(element));
                }
            } else {
                throw new RuntimeException("Expected Collection instance for " + className
                        + " but got " + obj.getClass().getName());
            }
        }
    }

    private static Object createEmptyCollection(String className) {
        if ("java.util.ArrayList".equals(className)) return new java.util.ArrayList<>();
        if ("java.util.LinkedList".equals(className)) return new java.util.LinkedList<>();
        if ("java.util.HashSet".equals(className)) return new java.util.HashSet<>();
        if ("java.util.LinkedHashSet".equals(className)) return new java.util.LinkedHashSet<>();
        if ("java.util.TreeSet".equals(className)) return new java.util.TreeSet<>();
        if ("java.util.HashMap".equals(className)) return new java.util.HashMap<>();
        if ("java.util.LinkedHashMap".equals(className)) return new java.util.LinkedHashMap<>();
        if ("java.util.TreeMap".equals(className)) return new java.util.TreeMap<>();
        if ("java.util.concurrent.ConcurrentHashMap".equals(className)) return new java.util.concurrent.ConcurrentHashMap<>();
        if ("java.util.ArrayDeque".equals(className)) return new java.util.ArrayDeque<>();
        try {
            Class<?> clazz = Class.forName(className);
            return OBJENESIS.newInstance(clazz);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot create collection: " + className, e);
        }
    }

    private void populateArray(Object array, ObjectSnapshot snap) {
        ObjectRef[] elements = snap.arrayElements();
        if (elements == null) return;

        Class<?> componentType = array.getClass().getComponentType();
        for (int i = 0; i < elements.length; i++) {
            Object value = resolve(elements[i]);
            if (componentType.isPrimitive()) {
                Array.set(array, i, value);
            } else {
                Array.set(array, i, value);
            }
        }
    }

    private void populateRegularObject(Object obj, ObjectSnapshot snap) {
        for (Map.Entry<String, ObjectRef> entry : snap.fields().entrySet()) {
            String fieldKey = entry.getKey();
            // fieldKey format: "com.example.ClassName.fieldName"
            int lastDot = fieldKey.lastIndexOf('.');
            if (lastDot < 0) continue;

            String declaringClassName = fieldKey.substring(0, lastDot);
            String fieldName = fieldKey.substring(lastDot + 1);

            try {
                Class<?> declaringClass = Class.forName(declaringClassName);
                Field field = declaringClass.getDeclaredField(fieldName);
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = resolve(entry.getValue());
                field.set(obj, value);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Cannot restore field '" + fieldKey
                        + "': declaring class not found", e);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException("Cannot restore field '" + fieldKey
                        + "': field no longer exists (class structure changed?)", e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot restore field '" + fieldKey
                        + "': field is inaccessible. Add appropriate --add-opens "
                        + "JVM flags if this is a module-protected field.", e);
            }
        }
    }

    private Class<?> resolveArrayComponentType(String arrayClassName) {
        // arrayClassName is like "[I", "[[Ljava.lang.String;", etc.
        try {
            Class<?> arrayClass = Class.forName(arrayClassName);
            return arrayClass.getComponentType();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot resolve array type: " + arrayClassName, e);
        }
    }
}
