package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.internal.ClassStructureHasher;
import ai.jacc.durableThreads.internal.ObjenesisHolder;
import ai.jacc.durableThreads.snapshot.ObjectKind;
import ai.jacc.durableThreads.snapshot.ObjectRef;
import ai.jacc.durableThreads.snapshot.ObjectSnapshot;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Value;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The final-fallback handler for ordinary application objects. Captures every
 * non-static, non-transient field by walking the JDI class hierarchy; restores
 * the object via Objenesis (no constructor) and sets each field reflectively.
 *
 * <p>Field keys are stored as {@code "<declaring.class>.<field>"} so the
 * restorer can locate the declaring class even when a name is shadowed up
 * the hierarchy.</p>
 *
 * <p>Lambda / hidden classes ({@code $$Lambda}) can't be loaded by name —
 * allocate produces a placeholder {@code new Object()} and populate silently
 * skips their fields. The real lambda dispatch uses a dynamic proxy.</p>
 */
public final class RegularObjectHandler implements TypeHandler {

    @Override
    public boolean capturesType(ObjectReference objRef, ReferenceType refType, String className) {
        return true; // final fallback
    }

    @Override
    public ObjectSnapshot capture(CaptureContext ctx, long snapId, ObjectReference objRef,
                                   ReferenceType refType, String className, String name) {
        Map<String, ObjectRef> fields = new LinkedHashMap<>();

        ReferenceType current = refType;
        Set<String> visitedTypes = new HashSet<>();

        while (current != null) {
            String typeName = current.name();
            if ("java.lang.Object".equals(typeName) || visitedTypes.contains(typeName)) break;
            visitedTypes.add(typeName);

            List<Field> jdiFields = current.fields();
            Map<Field, Value> fieldValues = objRef.getValues(jdiFields);

            for (Field jdiField : jdiFields) {
                if (jdiField.isStatic()) continue;
                if (JdiFieldAccess.isTransient(jdiField)) continue;

                String fieldKey = typeName + "." + jdiField.name();
                Value value = fieldValues.get(jdiField);
                fields.put(fieldKey, ctx.capture(value));
            }

            if (current instanceof ClassType) {
                current = ((ClassType) current).superclass();
            } else {
                break;
            }
        }

        byte[] structureHash = ClassStructureHasher.hashClassStructure(refType);
        return new ObjectSnapshot(snapId, className, ObjectKind.REGULAR,
                fields, null, structureHash, name);
    }

    @Override
    public boolean restoresSnapshot(ObjectSnapshot snap) {
        return snap.kind() == ObjectKind.REGULAR; // final fallback
    }

    @Override
    public Object allocate(RestoreContext ctx, ObjectSnapshot snap) {
        try {
            Class<?> clazz = Class.forName(snap.className());
            return ObjenesisHolder.get().newInstance(clazz);
        } catch (ClassNotFoundException e) {
            if (snap.className().contains("$$Lambda")) {
                // Hidden lambda class — the real dispatch uses a dynamic proxy,
                // so this object is only a placeholder.
                return new Object();
            }
            throw new RuntimeException("Cannot restore object of type: " + snap.className(), e);
        }
    }

    @Override
    public void populate(RestoreContext ctx, Object obj, ObjectSnapshot snap) {
        for (Map.Entry<String, ObjectRef> entry : snap.fields().entrySet()) {
            String fieldKey = entry.getKey();
            int lastDot = fieldKey.lastIndexOf('.');
            if (lastDot < 0) continue;

            String declaringClassName = fieldKey.substring(0, lastDot);
            String fieldName = fieldKey.substring(lastDot + 1);

            try {
                Class<?> declaringClass = Class.forName(declaringClassName);
                java.lang.reflect.Field field = declaringClass.getDeclaredField(fieldName);
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = ctx.resolve(entry.getValue());
                field.set(obj, value);
            } catch (ClassNotFoundException e) {
                if (declaringClassName.contains("$$Lambda")) continue;
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
}
