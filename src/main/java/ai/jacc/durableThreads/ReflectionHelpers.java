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
}
