package com.u1.durableThreads.internal;

import com.u1.durableThreads.snapshot.*;
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
        // Index snapshots by ID
        for (ObjectSnapshot snap : heap) {
            snapshotMap.put(snap.id(), snap);
        }

        // First pass: allocate all objects (without setting fields)
        for (ObjectSnapshot snap : heap) {
            allocate(snap);
        }

        // Second pass: set fields and array elements
        for (ObjectSnapshot snap : heap) {
            populate(snap);
        }

        return restored;
    }

    /**
     * Resolve an ObjectRef to a live Java object.
     */
    public Object resolve(ObjectRef ref) {
        return switch (ref) {
            case NullRef ignored -> null;
            case PrimitiveRef p -> p.value();
            case HeapRef h -> restored.get(h.id());
        };
    }

    private void allocate(ObjectSnapshot snap) {
        if (restored.containsKey(snap.id())) return;

        Object obj = switch (snap.kind()) {
            case STRING -> {
                // Strings are special: extract value directly
                ObjectRef valueRef = snap.fields().get("value");
                yield (valueRef instanceof PrimitiveRef p) ? p.value() : "";
            }
            case ARRAY -> {
                Class<?> componentType = resolveArrayComponentType(snap.className());
                int length = snap.arrayElements() != null ? snap.arrayElements().length : 0;
                yield Array.newInstance(componentType, length);
            }
            case REGULAR -> {
                try {
                    Class<?> clazz = Class.forName(snap.className());
                    yield OBJENESIS.newInstance(clazz);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Cannot restore object of type: " + snap.className(), e);
                }
            }
        };

        restored.put(snap.id(), obj);
    }

    private void populate(ObjectSnapshot snap) {
        Object obj = restored.get(snap.id());
        if (obj == null) return;

        switch (snap.kind()) {
            case STRING -> {} // Strings are immutable, already set during allocation
            case ARRAY -> populateArray(obj, snap);
            case REGULAR -> populateRegularObject(obj, snap);
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
            } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
                // Skip fields that can't be restored
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
