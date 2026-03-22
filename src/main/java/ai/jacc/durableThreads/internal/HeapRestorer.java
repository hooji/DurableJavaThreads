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
                // Strings, StringBuilder/StringBuffer, and immutable JDK types
                // captured via toString: extract value and reconstruct.
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
                    Object immutable = tryCreateImmutable(cn, content);
                    obj = immutable != null ? immutable : content; // fallback to java.lang.String
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
                // Boxed primitives: create directly from the captured value field
                // instead of Objenesis, since their final 'value' field can't be
                // set reflectively on Java 16+ without --add-opens.
                Object boxed = tryCreateBoxedPrimitive(snap);
                if (boxed != null) {
                    obj = boxed;
                    break;
                }
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
                // Boxed primitives were fully created during allocation
                if (tryCreateBoxedPrimitive(snap) == null) {
                    populateRegularObject(obj, snap);
                }
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

    /**
     * Try to create a boxed primitive (Integer, Long, etc.) from the snapshot's
     * captured 'value' field. Returns null if the snapshot isn't a boxed primitive.
     */
    private static Object tryCreateBoxedPrimitive(ObjectSnapshot snap) {
        String cn = snap.className();
        String valueKey = cn + ".value";
        ObjectRef valRef = snap.fields().get(valueKey);
        if (valRef == null) return null;

        Object rawValue = (valRef instanceof PrimitiveRef) ? ((PrimitiveRef) valRef).value() : null;
        if (rawValue == null) return null;

        switch (cn) {
            case "java.lang.Integer":    return (rawValue instanceof Number) ? ((Number) rawValue).intValue() : null;
            case "java.lang.Long":       return (rawValue instanceof Number) ? ((Number) rawValue).longValue() : null;
            case "java.lang.Double":     return (rawValue instanceof Number) ? ((Number) rawValue).doubleValue() : null;
            case "java.lang.Float":      return (rawValue instanceof Number) ? ((Number) rawValue).floatValue() : null;
            case "java.lang.Short":      return (rawValue instanceof Number) ? ((Number) rawValue).shortValue() : null;
            case "java.lang.Byte":       return (rawValue instanceof Number) ? ((Number) rawValue).byteValue() : null;
            case "java.lang.Character":  return (rawValue instanceof Character) ? rawValue : null;
            case "java.lang.Boolean":    return (rawValue instanceof Boolean) ? rawValue : null;
            default: return null;
        }
    }

    /**
     * Try to reconstruct an immutable JDK type from its string representation.
     * Returns null if the className isn't a known immutable type.
     */
    private static Object tryCreateImmutable(String className, String value) {
        switch (className) {
            case "java.math.BigDecimal":
                return new java.math.BigDecimal(value);
            case "java.math.BigInteger":
                return new java.math.BigInteger(value);
            case "java.util.UUID":
                return java.util.UUID.fromString(value);
            case "java.time.LocalDate":
                return java.time.LocalDate.parse(value);
            case "java.time.LocalTime":
                return java.time.LocalTime.parse(value);
            case "java.time.LocalDateTime":
                return java.time.LocalDateTime.parse(value);
            case "java.time.Instant":
                return java.time.Instant.parse(value);
            case "java.time.Duration":
                return java.time.Duration.parse(value);
            case "java.time.ZonedDateTime":
                return java.time.ZonedDateTime.parse(value);
            case "java.time.OffsetDateTime":
                return java.time.OffsetDateTime.parse(value);
            case "java.time.Period":
                return java.time.Period.parse(value);
            case "java.time.Year":
                return java.time.Year.parse(value);
            case "java.time.YearMonth":
                return java.time.YearMonth.parse(value);
            case "java.time.MonthDay":
                return java.time.MonthDay.parse(value);
            case "java.time.ZoneOffset":
                return java.time.ZoneOffset.of(value);
            case "java.time.ZoneId":
                return java.time.ZoneId.of(value);
            case "java.net.URI":
                return java.net.URI.create(value);
            default:
                // Check for enum types — restore by looking up the constant by name
                return tryCreateEnum(className, value);
        }
    }

    /**
     * Try to restore an enum constant by name. Returns null if not an enum.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object tryCreateEnum(String className, String constantName) {
        try {
            Class<?> clazz = Class.forName(className);
            // Walk up to find the actual enum class. On Java 8, enum constants
            // with method overrides are anonymous subclasses (e.g., TimeUnit$3)
            // where isEnum() returns false. The actual enum is the superclass.
            while (clazz != null && !clazz.isEnum()) {
                clazz = clazz.getSuperclass();
            }
            if (clazz != null && clazz.isEnum()) {
                return Enum.valueOf((Class<Enum>) clazz, constantName);
            }
        } catch (ClassNotFoundException e) {
            // Not a loadable class — not an enum
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Cannot restore enum '" + className
                    + "': constant '" + constantName + "' not found. "
                    + "The enum may have changed since the snapshot was taken.", e);
        }
        return null;
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
