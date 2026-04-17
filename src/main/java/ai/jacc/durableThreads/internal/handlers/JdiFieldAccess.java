package ai.jacc.durableThreads.internal.handlers;

import com.sun.jdi.ByteValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.Value;

/**
 * Small shared JDI field-reading helpers used by the type handlers.
 *
 * <p>Walks up the class hierarchy when searching by name, and returns sane
 * defaults when a field is absent or its value's type is unexpected — this
 * keeps handler code terse for the common field-extraction patterns.</p>
 */
public final class JdiFieldAccess {

    private JdiFieldAccess() {}

    /** Find a field by simple name, walking superclasses. */
    public static Field findField(ReferenceType type, String name) {
        Field f = type.fieldByName(name);
        if (f != null) return f;
        if (type instanceof ClassType && ((ClassType) type).superclass() != null) {
            return findField(((ClassType) type).superclass(), name);
        }
        return null;
    }

    public static int getIntField(ObjectReference obj, ReferenceType type, String fieldName) {
        Field f = findField(type, fieldName);
        if (f == null) return 0;
        Value v = obj.getValue(f);
        return (v instanceof IntegerValue) ? ((IntegerValue) v).value() : 0;
    }

    public static long getLongField(ObjectReference obj, ReferenceType type, String fieldName) {
        Field f = findField(type, fieldName);
        if (f == null) return 0;
        Value v = obj.getValue(f);
        return (v instanceof LongValue) ? ((LongValue) v).value() : 0;
    }

    public static short getShortField(ObjectReference obj, ReferenceType type, String fieldName) {
        Field f = findField(type, fieldName);
        if (f == null) return 0;
        Value v = obj.getValue(f);
        return (v instanceof ShortValue) ? ((ShortValue) v).value() : 0;
    }

    public static byte getByteField(ObjectReference obj, ReferenceType type, String fieldName) {
        Field f = findField(type, fieldName);
        if (f == null) return 0;
        Value v = obj.getValue(f);
        return (v instanceof ByteValue) ? ((ByteValue) v).value() : 0;
    }

    /** Check the {@code ACC_TRANSIENT} bit on a JDI field. */
    public static boolean isTransient(Field field) {
        return (field.modifiers() & 0x0080) != 0;
    }
}
