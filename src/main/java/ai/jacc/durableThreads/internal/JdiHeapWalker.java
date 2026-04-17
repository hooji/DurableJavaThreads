package ai.jacc.durableThreads.internal;

import ai.jacc.durableThreads.internal.handlers.CaptureContext;
import ai.jacc.durableThreads.internal.handlers.TypeHandler;
import ai.jacc.durableThreads.internal.handlers.TypeHandlerRegistry;
import ai.jacc.durableThreads.snapshot.*;
import com.sun.jdi.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks the object graph via JDI to capture objects reachable from local
 * variables in a suspended thread.
 *
 * <p>Coordinates: primitives/strings/arrays are captured directly here;
 * everything else is delegated to a {@link TypeHandler} chosen by
 * {@link TypeHandlerRegistry} in priority order.</p>
 *
 * <p>JDI-based capture bypasses Java module encapsulation and avoids the
 * need for captured objects to implement {@code Serializable} — field values
 * are read directly through JDI mirrors.</p>
 */
public final class JdiHeapWalker {

    private long nextId = 1;
    private final Map<Long, Long> jdiIdToSnapId = new HashMap<>();
    private final List<ObjectSnapshot> snapshots = new ArrayList<>();

    /**
     * Maps JDI uniqueID to a user-assigned name. When an object with a matching
     * uniqueID is captured, the resulting ObjectSnapshot gets the name assigned.
     */
    private final Map<Long, String> jdiIdToName = new HashMap<>();

    /** Callback handed to TypeHandlers so they can recurse on child values. */
    private final CaptureContext captureContext = this::capture;

    /**
     * Register a JDI object reference as a named object. When this object is
     * encountered during heap walking, its ObjectSnapshot will carry the name.
     */
    public void registerNamedObject(long jdiUniqueId, String name) {
        jdiIdToName.put(jdiUniqueId, name);
    }

    /**
     * Capture a JDI Value into the snapshot heap. Returns a leaf
     * ({@link NullRef}, {@link PrimitiveRef}) for primitives/strings/null,
     * or a {@link HeapRef} into the snapshot heap for an object.
     */
    public ObjectRef capture(Value value) {
        if (value == null) {
            return NullRef.INSTANCE;
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

        return NullRef.INSTANCE;
    }

    /** Get all captured object snapshots. */
    public List<ObjectSnapshot> getSnapshots() {
        return Collections.unmodifiableList(snapshots);
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

        // Already visited — return existing reference. Cycle-safe: the id
        // mapping is installed BEFORE the handler recurses into fields.
        Long existingSnapId = jdiIdToSnapId.get(jdiId);
        if (existingSnapId != null) {
            return new HeapRef(existingSnapId);
        }

        long snapId = nextId++;
        jdiIdToSnapId.put(jdiId, snapId);

        String name = jdiIdToName.get(jdiId);
        ReferenceType refType = objRef.referenceType();
        String className = refType.name();

        ObjectSnapshot snap;
        if (objRef instanceof ArrayReference) {
            // JDI display name ("int[]", "java.lang.String[][]") → JVM internal
            // name ("[I", "[[Ljava.lang.String;") so restore's Class.forName works.
            String jvmArrayName = toJvmArrayName(className);
            snap = captureArray(snapId, (ArrayReference) objRef, jvmArrayName, name);
        } else {
            TypeHandler handler = TypeHandlerRegistry.forCapture(objRef, refType, className);
            snap = handler.capture(captureContext, snapId, objRef, refType, className, name);
        }

        snapshots.add(snap);
        return new HeapRef(snapId);
    }

    private ObjectSnapshot captureArray(long snapId, ArrayReference arrRef, String className, String name) {
        List<Value> values = arrRef.getValues();
        ObjectRef[] elements = new ObjectRef[values.size()];

        for (int i = 0; i < values.size(); i++) {
            elements[i] = capture(values.get(i));
        }

        return new ObjectSnapshot(snapId, className, ObjectKind.ARRAY,
                Collections.<String, ObjectRef>emptyMap(), elements, null, name);
    }

    /**
     * Convert a JDI array type display name to a JVM internal name.
     * JDI gives "int[]", "byte[][]", "java.lang.String[]"; Class.forName
     * needs "[I", "[[B", "[Ljava.lang.String;".
     */
    static String toJvmArrayName(String jdiName) {
        int dims = 0;
        String base = jdiName;
        while (base.endsWith("[]")) {
            dims++;
            base = base.substring(0, base.length() - 2);
        }
        if (dims == 0) return jdiName;

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
