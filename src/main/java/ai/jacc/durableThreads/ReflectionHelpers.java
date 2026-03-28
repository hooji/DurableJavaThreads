package ai.jacc.durableThreads;

import java.lang.reflect.Method;

/**
 * Reflection utilities for the restore operation: method lookup by JVM
 * descriptor, dummy argument creation, receiver resolution, and bottom
 * frame invocation.
 */
final class ReflectionHelpers {

    private ReflectionHelpers() {}

    /**
     * Find a method by name and JVM descriptor, searching the class hierarchy.
     */
    static Method findMethod(Class<?> clazz, String name, String desc) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name) && descriptorMatches(m, desc)) {
                return m;
            }
        }
        if (clazz.getSuperclass() != null) {
            return findMethod(clazz.getSuperclass(), name, desc);
        }
        return null;
    }

    /**
     * Check if a reflection Method matches a JVM method descriptor string.
     */
    static boolean descriptorMatches(Method m, String desc) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> param : m.getParameterTypes()) {
            sb.append(typeToDescriptor(param));
        }
        sb.append(")");
        sb.append(typeToDescriptor(m.getReturnType()));
        return sb.toString().equals(desc);
    }

    /**
     * Convert a Java Class to its JVM type descriptor string.
     */
    static String typeToDescriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) return type.getName().replace('.', '/');
        return "L" + type.getName().replace('.', '/') + ";";
    }

    /**
     * Create an array of type-appropriate default values for method parameters.
     */
    static Object[] createDummyArgs(Class<?>[] paramTypes) {
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = defaultValue(paramTypes[i]);
        }
        return args;
    }

    /**
     * Return the default value for a type (0/false/null).
     */
    static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    /**
     * Check if a throwable's cause chain contains an instance of the given type.
     */
    static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
