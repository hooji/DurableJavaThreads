package ai.jacc.durableThreads.internal;

import ai.jacc.durableThreads.internal.handlers.RestoreContext;
import ai.jacc.durableThreads.internal.handlers.TypeHandler;
import ai.jacc.durableThreads.internal.handlers.TypeHandlerRegistry;
import ai.jacc.durableThreads.snapshot.*;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the captured object graph from a snapshot.
 *
 * <p>Two-pass:</p>
 * <ol>
 *   <li><b>Allocate</b> every object (no cross-object field wiring).</li>
 *   <li><b>Populate</b> fields/elements (all {@link HeapRef} targets already
 *       exist from pass 1, so cycles resolve naturally).</li>
 * </ol>
 *
 * <p>Primitives/strings/arrays are handled directly here. Everything else is
 * delegated to a {@link TypeHandler} selected by {@link TypeHandlerRegistry}.</p>
 */
public final class HeapRestorer {

    private final Map<Long, Object> restored = new HashMap<>();
    private final Map<Long, ObjectSnapshot> snapshotMap = new HashMap<>();

    /** Callback handed to TypeHandlers so they can resolve child refs. */
    private final RestoreContext restoreContext = this::resolve;

    /** Restore all objects from the snapshot heap. */
    public Map<Long, Object> restoreAll(List<ObjectSnapshot> heap) {
        return restoreAll(heap, null);
    }

    /**
     * Restore all objects from the snapshot heap, substituting named objects
     * with live replacements from the provided map.
     *
     * @throws RuntimeException if namedReplacements contains names not found
     */
    public Map<Long, Object> restoreAll(List<ObjectSnapshot> heap,
                                          Map<String, Object> namedReplacements) {
        for (ObjectSnapshot snap : heap) {
            snapshotMap.put(snap.id(), snap);
        }

        Map<String, Long> nameToSnapId = new HashMap<>();
        for (ObjectSnapshot snap : heap) {
            if (snap.name() != null) {
                nameToSnapId.put(snap.name(), snap.id());
            }
        }

        if (namedReplacements != null) {
            List<String> unknownNames = new ArrayList<>();
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

        // Pass 1: allocate all objects. For named objects with replacements,
        // use the replacement directly (no allocation, no field population).
        for (ObjectSnapshot snap : heap) {
            if (snap.name() != null && namedReplacements != null
                    && namedReplacements.containsKey(snap.name())) {
                restored.put(snap.id(), namedReplacements.get(snap.name()));
            } else {
                allocate(snap);
            }
        }

        if (!nameToSnapId.isEmpty()) {
            List<String> unreplaced = new ArrayList<>();
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

        // Pass 2: populate fields/elements, skipping replaced objects.
        for (ObjectSnapshot snap : heap) {
            if (snap.name() != null && namedReplacements != null
                    && namedReplacements.containsKey(snap.name())) {
                continue;
            }
            populate(snap);
        }

        return restored;
    }

    /** Resolve an ObjectRef to a live Java object. */
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
            case ARRAY: {
                Class<?> componentType = resolveArrayComponentType(snap.className());
                int length = snap.arrayElements() != null ? snap.arrayElements().length : 0;
                obj = Array.newInstance(componentType, length);
                break;
            }
            case STRING: {
                TypeHandler handler = TypeHandlerRegistry.forRestore(snap);
                if (handler != null) {
                    obj = handler.allocate(restoreContext, snap);
                } else {
                    // Plain java.lang.String — unwrap the captured content.
                    obj = extractString(snap);
                }
                break;
            }
            case REGULAR:
            case COLLECTION: {
                TypeHandler handler = TypeHandlerRegistry.forRestore(snap);
                if (handler == null) {
                    throw new RuntimeException(
                            "No TypeHandler matched for snapshot kind=" + snap.kind()
                            + " className=" + snap.className());
                }
                obj = handler.allocate(restoreContext, snap);
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
                // All STRING-kind types are fully constructed during allocate().
                return;
            case ARRAY:
                populateArray(obj, snap);
                return;
            case REGULAR:
            case COLLECTION: {
                TypeHandler handler = TypeHandlerRegistry.forRestore(snap);
                if (handler != null) {
                    handler.populate(restoreContext, obj, snap);
                }
                return;
            }
            default:
                return;
        }
    }

    private void populateArray(Object array, ObjectSnapshot snap) {
        ObjectRef[] elements = snap.arrayElements();
        if (elements == null) return;

        for (int i = 0; i < elements.length; i++) {
            Object value = resolve(elements[i]);
            Array.set(array, i, value);
        }
    }

    private static String extractString(ObjectSnapshot snap) {
        ObjectRef valueRef = snap.fields().get("value");
        if (valueRef instanceof PrimitiveRef) {
            Object val = ((PrimitiveRef) valueRef).value();
            if (val instanceof String) return (String) val;
        }
        return "";
    }

    private Class<?> resolveArrayComponentType(String arrayClassName) {
        try {
            Class<?> arrayClass = Class.forName(arrayClassName);
            return arrayClass.getComponentType();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot resolve array type: " + arrayClassName, e);
        }
    }
}
