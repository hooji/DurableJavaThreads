package ai.jacc.durableThreads.internal.handlers;

import ai.jacc.durableThreads.snapshot.ObjectKind;
import ai.jacc.durableThreads.snapshot.ObjectRef;
import ai.jacc.durableThreads.snapshot.ObjectSnapshot;
import ai.jacc.durableThreads.snapshot.PrimitiveRef;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Value;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link StringBuilder} and {@link StringBuffer}. Captured by reading the
 * internal character/byte array directly via JDI (avoiding a {@code toString()}
 * invocation, which would require resuming the suspended thread). Restored
 * by invoking the corresponding {@code String}-accepting constructor.
 *
 * <p>Handles both the Java 8 char-array layout and the Java 9+ compact-string
 * byte-array + {@code coder} layout.</p>
 */
public final class StringBuilderHandler implements TypeHandler {

    @Override
    public boolean capturesType(ObjectReference objRef, ReferenceType refType, String className) {
        return "java.lang.StringBuilder".equals(className)
                || "java.lang.StringBuffer".equals(className);
    }

    @Override
    public ObjectSnapshot capture(CaptureContext ctx, long snapId, ObjectReference objRef,
                                   ReferenceType refType, String className, String name) {
        String content = readContent(objRef, refType, className);
        Map<String, ObjectRef> fields = new LinkedHashMap<>();
        fields.put("value", new PrimitiveRef(content));
        return new ObjectSnapshot(snapId, className, ObjectKind.STRING,
                fields, null, null, name);
    }

    @Override
    public boolean restoresSnapshot(ObjectSnapshot snap) {
        return snap.kind() == ObjectKind.STRING
                && ("java.lang.StringBuilder".equals(snap.className())
                    || "java.lang.StringBuffer".equals(snap.className()));
    }

    @Override
    public Object allocate(RestoreContext ctx, ObjectSnapshot snap) {
        String content = extractString(snap);
        if ("java.lang.StringBuilder".equals(snap.className())) {
            return new StringBuilder(content);
        }
        return new StringBuffer(content);
    }

    private static String readContent(ObjectReference objRef, ReferenceType refType, String className) {
        try {
            Field countField = JdiFieldAccess.findField(refType, "count");
            Field valueField = JdiFieldAccess.findField(refType, "value");
            Field coderField = JdiFieldAccess.findField(refType, "coder");

            if (valueField == null || countField == null) return "";
            Value countVal = objRef.getValue(countField);
            Value coderVal = coderField != null ? objRef.getValue(coderField) : null;
            Value valueVal = objRef.getValue(valueField);
            int count = (countVal instanceof IntegerValue) ? ((IntegerValue) countVal).value() : 0;
            int coder = (coderVal instanceof IntegerValue) ? ((IntegerValue) coderVal).value() : 0;

            if (!(valueVal instanceof ArrayReference) || count <= 0) return "";
            ArrayReference arr = (ArrayReference) valueVal;
            List<Value> vals = arr.getValues(0, Math.min(count, arr.length()));
            if (coderField == null || vals.get(0) instanceof CharValue) {
                // Java 8: value is a char[] array
                char[] chars = new char[count];
                for (int i = 0; i < count; i++) {
                    chars[i] = (vals.get(i) instanceof CharValue) ? ((CharValue) vals.get(i)).value() : 0;
                }
                return new String(chars);
            }
            if (coder == 0) {
                // Java 9+ LATIN1: each byte is a char
                byte[] bytes = new byte[count];
                for (int i = 0; i < count; i++) {
                    bytes[i] = (vals.get(i) instanceof ByteValue) ? ((ByteValue) vals.get(i)).value() : 0;
                }
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
            // Java 9+ UTF16: two bytes per char
            List<Value> utf16Vals = arr.getValues(0, count * 2);
            byte[] bytes = new byte[count * 2];
            for (int i = 0; i < count * 2; i++) {
                bytes[i] = (utf16Vals.get(i) instanceof ByteValue) ? ((ByteValue) utf16Vals.get(i)).value() : 0;
            }
            return new String(bytes, StandardCharsets.UTF_16LE);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Cannot read " + className + " content via JDI. "
                    + "Thread freeze cannot proceed with incomplete state.", e);
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
}
