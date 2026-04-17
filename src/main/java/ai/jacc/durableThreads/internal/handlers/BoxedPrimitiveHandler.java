package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.snapshot.ObjectKind;
import ai.jacc.durableThreads.snapshot.ObjectRef;
import ai.jacc.durableThreads.snapshot.ObjectSnapshot;
import ai.jacc.durableThreads.snapshot.PrimitiveRef;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Value;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Boxed primitives (Integer, Long, Double, Float, Short, Byte, Character,
 * Boolean). Captured by reading the single private {@code value} field and
 * restored by direct construction. Objenesis cannot be used because the
 * {@code value} field is final and cannot be set reflectively on Java 16+
 * without {@code --add-opens}.
 */
public final class BoxedPrimitiveHandler implements TypeHandler {

    private static final Map<String, String> BOXED_PRIMITIVES = new LinkedHashMap<>();
    static {
        BOXED_PRIMITIVES.put("java.lang.Integer", "I");
        BOXED_PRIMITIVES.put("java.lang.Long", "J");
        BOXED_PRIMITIVES.put("java.lang.Double", "D");
        BOXED_PRIMITIVES.put("java.lang.Float", "F");
        BOXED_PRIMITIVES.put("java.lang.Short", "S");
        BOXED_PRIMITIVES.put("java.lang.Byte", "B");
        BOXED_PRIMITIVES.put("java.lang.Character", "C");
        BOXED_PRIMITIVES.put("java.lang.Boolean", "Z");
    }

    @Override
    public boolean capturesType(ObjectReference objRef, ReferenceType refType, String className) {
        return BOXED_PRIMITIVES.containsKey(className);
    }

    @Override
    public ObjectSnapshot capture(CaptureContext ctx, long snapId, ObjectReference objRef,
                                   ReferenceType refType, String className, String name) {
        Field valueField = refType.fieldByName("value");
        ObjectRef innerRef;
        if (valueField != null) {
            Value inner = objRef.getValue(valueField);
            innerRef = (inner instanceof PrimitiveValue)
                    ? primitiveToRef((PrimitiveValue) inner)
                    : new PrimitiveRef(0);
        } else {
            innerRef = new PrimitiveRef(0);
        }
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put(className + ".value", innerRef);
        return new ObjectSnapshot(snapId, className, ObjectKind.REGULAR,
                fields, null, null, name);
    }

    @Override
    public boolean restoresSnapshot(ObjectSnapshot snap) {
        return snap.kind() == ObjectKind.REGULAR
                && BOXED_PRIMITIVES.containsKey(snap.className());
    }

    @Override
    public Object allocate(RestoreContext ctx, ObjectSnapshot snap) {
        String cn = snap.className();
        ObjectRef valRef = snap.fields().get(cn + ".value");
        Object rawValue = (valRef instanceof PrimitiveRef) ? ((PrimitiveRef) valRef).value() : null;
        if (rawValue == null) {
            throw new RuntimeException("Boxed primitive " + cn + " has no captured value");
        }
        switch (cn) {
            case "java.lang.Integer":   return ((Number) rawValue).intValue();
            case "java.lang.Long":      return ((Number) rawValue).longValue();
            case "java.lang.Double":    return ((Number) rawValue).doubleValue();
            case "java.lang.Float":     return ((Number) rawValue).floatValue();
            case "java.lang.Short":     return ((Number) rawValue).shortValue();
            case "java.lang.Byte":      return ((Number) rawValue).byteValue();
            case "java.lang.Character": return (Character) rawValue;
            case "java.lang.Boolean":   return (Boolean) rawValue;
            default:
                throw new IllegalStateException("Unknown boxed primitive: " + cn);
        }
    }

    private static ObjectRef primitiveToRef(PrimitiveValue pv) {
        if (pv instanceof com.sun.jdi.BooleanValue) return new PrimitiveRef(((com.sun.jdi.BooleanValue) pv).value());
        if (pv instanceof com.sun.jdi.ByteValue) return new PrimitiveRef(((com.sun.jdi.ByteValue) pv).value());
        if (pv instanceof com.sun.jdi.CharValue) return new PrimitiveRef(((com.sun.jdi.CharValue) pv).value());
        if (pv instanceof com.sun.jdi.ShortValue) return new PrimitiveRef(((com.sun.jdi.ShortValue) pv).value());
        if (pv instanceof com.sun.jdi.IntegerValue) return new PrimitiveRef(((com.sun.jdi.IntegerValue) pv).value());
        if (pv instanceof com.sun.jdi.LongValue) return new PrimitiveRef(((com.sun.jdi.LongValue) pv).value());
        if (pv instanceof com.sun.jdi.FloatValue) return new PrimitiveRef(((com.sun.jdi.FloatValue) pv).value());
        if (pv instanceof com.sun.jdi.DoubleValue) return new PrimitiveRef(((com.sun.jdi.DoubleValue) pv).value());
        return new PrimitiveRef(0);
    }
}
