package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.snapshot.ObjectKind;
import ai.jacc.durableThreads.snapshot.ObjectRef;
import ai.jacc.durableThreads.snapshot.ObjectSnapshot;
import ai.jacc.durableThreads.snapshot.PrimitiveRef;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enum constants (any package, including JDK enums like {@code TimeUnit}).
 * Captured by reading the {@code name} field inherited from
 * {@code java.lang.Enum}; restored via {@code Enum.valueOf}.
 *
 * <p>On Java 8, enum constants with method overrides are anonymous
 * subclasses (e.g., {@code TimeUnit$3}). The capture side records the
 * actual enum class name (walking up to the ancestor whose direct
 * superclass is {@code java.lang.Enum}) so {@code valueOf} works.</p>
 */
public final class EnumHandler implements TypeHandler {

    @Override
    public boolean capturesType(ObjectReference objRef, ReferenceType refType, String className) {
        if (!(refType instanceof ClassType)) return false;
        ClassType ct = ((ClassType) refType).superclass();
        while (ct != null) {
            if ("java.lang.Enum".equals(ct.name())) return true;
            ct = ct.superclass();
        }
        return false;
    }

    @Override
    public ObjectSnapshot capture(CaptureContext ctx, long snapId, ObjectReference objRef,
                                   ReferenceType refType, String className, String name) {
        String enumClassName = className;
        if (refType instanceof ClassType) {
            ClassType ct = (ClassType) refType;
            while (ct.superclass() != null) {
                if ("java.lang.Enum".equals(ct.superclass().name())) {
                    enumClassName = ct.name();
                    break;
                }
                ct = ct.superclass();
            }
        }

        String constantName = "";
        Field nameField = JdiFieldAccess.findField(refType, "name");
        if (nameField != null) {
            Value v = objRef.getValue(nameField);
            if (v instanceof StringReference) {
                constantName = ((StringReference) v).value();
            }
        }
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef(constantName));
        return new ObjectSnapshot(snapId, enumClassName, ObjectKind.STRING,
                fields, null, null, name);
    }

    @Override
    public boolean restoresSnapshot(ObjectSnapshot snap) {
        if (snap.kind() != ObjectKind.STRING) return false;
        String cn = snap.className();
        if ("java.lang.String".equals(cn)) return false;
        if ("java.lang.StringBuilder".equals(cn)) return false;
        if ("java.lang.StringBuffer".equals(cn)) return false;
        if (ImmutableJdkHandler.isImmutable(cn)) return false;
        return isEnumClass(cn);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Object allocate(RestoreContext ctx, ObjectSnapshot snap) {
        String constantName = extractString(snap);
        try {
            Class<?> clazz = Class.forName(snap.className());
            while (clazz != null && !clazz.isEnum()) {
                clazz = clazz.getSuperclass();
            }
            if (clazz != null && clazz.isEnum()) {
                return Enum.valueOf((Class<Enum>) clazz, constantName);
            }
            throw new RuntimeException("Class " + snap.className() + " is not an enum");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot load enum class " + snap.className(), e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Cannot restore enum '" + snap.className()
                    + "': constant '" + constantName + "' not found. "
                    + "The enum may have changed since the snapshot was taken.", e);
        }
    }

    private static String extractString(ObjectSnapshot snap) {
        ObjectRef valueRef = snap.fields().get("value");
        if (valueRef instanceof PrimitiveRef) {
            Object v = ((PrimitiveRef) valueRef).value();
            if (v instanceof String) return (String) v;
        }
        return "";
    }

    private static boolean isEnumClass(String className) {
        try {
            Class<?> c = Class.forName(className);
            while (c != null && !c.isEnum()) {
                c = c.getSuperclass();
            }
            return c != null && c.isEnum();
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
