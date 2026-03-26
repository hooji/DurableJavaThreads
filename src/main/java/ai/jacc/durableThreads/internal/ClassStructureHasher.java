package ai.jacc.durableThreads.internal;

import com.sun.jdi.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Computes SHA-256 hashes of class field structure for detecting incompatible
 * changes to heap object classes between freeze and restore.
 *
 * <p>The hash covers the class hierarchy's non-static, non-transient fields:
 * their names, types, and declaring class. This detects added/removed/renamed
 * fields or type changes that would cause silent data corruption during restore.</p>
 *
 * <p>This does NOT hash method bytecode (that's {@link BytecodeHasher}'s job).
 * It only hashes the data layout — the "shape" of the object.</p>
 */
public final class ClassStructureHasher {

    private ClassStructureHasher() {}

    /**
     * Hash a class structure from a JDI ReferenceType (used at freeze time).
     *
     * @param refType the JDI type to hash
     * @return SHA-256 hash of the field structure, or empty array on failure
     */
    public static byte[] hashClassStructure(ReferenceType refType) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Walk class hierarchy
            ReferenceType current = refType;
            while (current != null && !current.name().equals("java.lang.Object")) {
                digest.update(current.name().getBytes(StandardCharsets.UTF_8));

                for (Field field : current.fields()) {
                    if (field.isStatic()) continue;
                    if ((field.modifiers() & 0x0080) != 0) continue; // ACC_TRANSIENT

                    digest.update(field.name().getBytes(StandardCharsets.UTF_8));
                    digest.update(field.typeName().getBytes(StandardCharsets.UTF_8));
                }

                if (current instanceof ClassType) {
                    current = ((ClassType) current).superclass();
                } else {
                    break;
                }
            }

            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /**
     * Hash a class structure from reflection (used at restore time to verify).
     *
     * @param clazz the class to hash
     * @return SHA-256 hash of the field structure, or empty array on failure
     */
    public static byte[] hashClassStructure(Class<?> clazz) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                digest.update(current.getName().getBytes(StandardCharsets.UTF_8));

                for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    if (java.lang.reflect.Modifier.isTransient(field.getModifiers())) continue;

                    digest.update(field.getName().getBytes(StandardCharsets.UTF_8));
                    // Use the same format as JDI's field.typeName() so hashes
                    // match between freeze (JDI) and restore (reflection).
                    // JDI returns "byte[]", "int[][]", "com.foo.Bar" etc.
                    // Reflection's getType().getName() returns "[B", "[[I",
                    // "com.foo.Bar" — different for array types.
                    digest.update(toJdiTypeName(field.getType()).getBytes(StandardCharsets.UTF_8));
                }

                current = current.getSuperclass();
            }

            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /**
     * Convert a Java reflection Class to the type name format that JDI uses.
     * JDI uses human-readable names like "byte[]", "int[][]", "java.lang.String".
     * Reflection uses JVM internal names like "[B", "[[I", "java.lang.String".
     */
    private static String toJdiTypeName(Class<?> type) {
        if (type.isArray()) {
            // Count array dimensions and find the component type
            int dims = 0;
            Class<?> component = type;
            while (component.isArray()) {
                dims++;
                component = component.getComponentType();
            }
            StringBuilder sb = new StringBuilder(component.getName());
            for (int i = 0; i < dims; i++) {
                sb.append("[]");
            }
            return sb.toString();
        }
        return type.getName();
    }
}
