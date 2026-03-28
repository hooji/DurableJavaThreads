package ai.jacc.durableThreads.internal;

import ai.jacc.durableThreads.snapshot.*;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Walks the object graph reachable from local variables and captures it
 * into a list of {@link ObjectSnapshot} records.
 */
public final class HeapWalker {

    private long nextId = 1;
    private final IdentityHashMap<Object, Long> visited = new IdentityHashMap<>();
    private final List<ObjectSnapshot> snapshots = new ArrayList<>();

    /**
     * Capture an object and all objects transitively reachable from it.
     *
     * @param obj the root object (may be null)
     * @return an ObjectRef pointing to this object in the snapshot heap
     */
    public ObjectRef capture(Object obj) {
        if (obj == null) {
            return new NullRef();
        }

        // Primitives boxed as wrapper types
        if (isPrimitiveWrapper(obj)) {
            return new PrimitiveRef((java.io.Serializable) obj);
        }

        // Already visited — return existing reference
        Long existingId = visited.get(obj);
        if (existingId != null) {
            return new HeapRef(existingId);
        }

        long id = nextId++;
        visited.put(obj, id);

        Class<?> clazz = obj.getClass();

        if (clazz == String.class) {
            // Strings are captured as STRING kind with their value in a special field
            Map<String, ObjectRef> fields = new LinkedHashMap<>();
            fields.put("value", new PrimitiveRef((String) obj));
            snapshots.add(new ObjectSnapshot(id, "java.lang.String", ObjectKind.STRING, fields, null));
        } else if (clazz.isArray()) {
            captureArray(id, obj, clazz);
        } else {
            captureRegularObject(id, obj, clazz);
        }

        return new HeapRef(id);
    }

    /**
     * Get all captured object snapshots.
     */
    public List<ObjectSnapshot> getSnapshots() {
        return Collections.unmodifiableList(snapshots);
    }

    private void captureArray(long id, Object array, Class<?> arrayClass) {
        int length = Array.getLength(array);
        Class<?> componentType = arrayClass.getComponentType();
        ObjectRef[] elements = new ObjectRef[length];

        if (componentType.isPrimitive()) {
            for (int i = 0; i < length; i++) {
                Object element = Array.get(array, i); // auto-boxes
                elements[i] = new PrimitiveRef((java.io.Serializable) element);
            }
        } else {
            for (int i = 0; i < length; i++) {
                elements[i] = capture(Array.get(array, i));
            }
        }

        snapshots.add(new ObjectSnapshot(id, arrayClass.getName(), ObjectKind.ARRAY, java.util.Collections.<String, ObjectRef>emptyMap(), elements));
    }

    private void captureRegularObject(long id, Object obj, Class<?> clazz) {
        Map<String, ObjectRef> fields = new LinkedHashMap<>();

        // Walk the class hierarchy
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (Modifier.isTransient(field.getModifiers())) continue;

                field.setAccessible(true);
                try {
                    Object value = field.get(obj);
                    String fieldKey = current.getName() + "." + field.getName();
                    if (field.getType().isPrimitive()) {
                        fields.put(fieldKey, new PrimitiveRef((java.io.Serializable) value));
                    } else {
                        fields.put(fieldKey, capture(value));
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Cannot capture field '"
                            + current.getName() + "." + field.getName()
                            + "': field is inaccessible. Add appropriate --add-opens "
                            + "JVM flags if this is a module-protected field.", e);
                }
            }
            current = current.getSuperclass();
        }

        snapshots.add(new ObjectSnapshot(id, clazz.getName(), ObjectKind.REGULAR, fields, null));
    }

    private static boolean isPrimitiveWrapper(Object obj) {
        return obj instanceof Boolean || obj instanceof Byte || obj instanceof Character
                || obj instanceof Short || obj instanceof Integer || obj instanceof Long
                || obj instanceof Float || obj instanceof Double;
    }
}
