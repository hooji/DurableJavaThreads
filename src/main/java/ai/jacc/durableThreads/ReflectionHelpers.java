package ai.jacc.durableThreads;

import ai.jacc.durableThreads.internal.HeapRestorer;
import ai.jacc.durableThreads.snapshot.FrameSnapshot;
import ai.jacc.durableThreads.snapshot.ThreadSnapshot;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * Reflection utilities for the restore operation: method lookup by JVM
 * descriptor, dummy argument creation, receiver resolution, and bottom
 * frame invocation.
 */
final class ReflectionHelpers {

    private ReflectionHelpers() {}

    /**
     * Reflectively invoke the bottom (entry-point) frame's method to start
     * the replay call chain. The instrumented prologue takes over immediately.
     */
    static void invokeBottomFrame(FrameSnapshot bottomFrame,
                                  Map<Long, Object> restoredHeap,
                                  HeapRestorer heapRestorer,
                                  ThreadSnapshot snapshot) throws Exception {
        String className = bottomFrame.className().replace('/', '.');
        Class<?> clazz = Class.forName(className);

        Method method = findMethod(clazz, bottomFrame.methodName(),
                bottomFrame.methodSignature());
        if (method == null) {
            throw new RuntimeException("Cannot find method: " + className + "."
                    + bottomFrame.methodName() + bottomFrame.methodSignature());
        }

        method.setAccessible(true);

        Object[] args = createDummyArgs(method.getParameterTypes());
        Object receiver = null;

        if (!Modifier.isStatic(method.getModifiers())) {
            receiver = findOrCreateReceiver(clazz, bottomFrame, restoredHeap, heapRestorer);
        }

        method.invoke(receiver, args);
    }

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
     * Find the receiver ("this") for the bottom frame from the snapshot's
     * restored heap, or create an uninitialized instance via Objenesis.
     */
    static Object findOrCreateReceiver(Class<?> clazz, FrameSnapshot frame,
                                       Map<Long, Object> restoredHeap,
                                       HeapRestorer heapRestorer) {
        for (ai.jacc.durableThreads.snapshot.LocalVariable local : frame.locals()) {
            if (local.slot() == 0 && local.name().equals("this")) {
                Object resolved = heapRestorer.resolve(local.value());
                if (resolved != null) return resolved;
            }
        }

        // 'this' was not captured or could not be resolved from the heap.
        // Fall back to creating an uninitialized instance via Objenesis —
        // the actual field values will be set later by the JDI worker.
        try {
            org.objenesis.ObjenesisStd objenesis = new org.objenesis.ObjenesisStd(true);
            return objenesis.newInstance(clazz);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create receiver instance of " + clazz.getName()
                    + ". The 'this' reference was not captured in the snapshot and Objenesis"
                    + " fallback failed.", e);
        }
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
