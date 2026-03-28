package ai.jacc.durableThreads;

import com.sun.jdi.*;
import ai.jacc.durableThreads.snapshot.*;

import java.util.*;

/**
 * JDI local variable manipulation: preloads classes, matches JDI frames to
 * snapshot frames, and sets local variables via JDI in a single pass.
 *
 * <p>Extracted from ThreadRestorer (Stage 4 refactoring).</p>
 */
final class JdiLocalSetter {

    private JdiLocalSetter() {}

    /**
     * Pre-load all classes referenced by snapshot local variable types.
     * This prevents ClassNotLoadedException during setValue() later, which
     * is critical because forceLoadClass uses invokeMethod (which resumes
     * the thread and invalidates cached StackFrame references).
     */
    static void preloadSnapshotClasses(VirtualMachine vm, ThreadReference threadRef,
                                       ThreadSnapshot snapshot) {
        Set<String> classNames = new LinkedHashSet<>();
        for (FrameSnapshot frame : snapshot.frames()) {
            for (ai.jacc.durableThreads.snapshot.LocalVariable local : frame.locals()) {
                String sig = local.typeDescriptor();
                if (sig != null && sig.startsWith("L") && sig.endsWith(";")) {
                    // Convert type signature "Ljava/util/concurrent/TimeUnit;" to class name
                    String className = sig.substring(1, sig.length() - 1).replace('/', '.');
                    classNames.add(className);
                }
            }
        }

        for (String className : classNames) {
            // First, load the class locally (we're in the same JVM).
            // This ensures the class is loaded even if JDI's invokeMethod fails
            // (e.g., on Java 8 where invokeMethod fails for WAITING threads).
            try {
                Class.forName(className);
            } catch (ClassNotFoundException ignored) {}

            // Check if JDI can see the class now
            if (!vm.classesByName(className).isEmpty()) continue;

            // Try to force-load via JDI as well (for cross-classloader visibility)
            forceLoadClass(vm, threadRef, className);
        }
    }

    private static void forceLoadClass(VirtualMachine vm, ThreadReference threadRef,
                                       String className) {
        try {
            // Find java.lang.Class in the target JVM
            List<ReferenceType> classTypes = vm.classesByName("java.lang.Class");
            if (classTypes.isEmpty()) return;
            ClassType classType = (ClassType) classTypes.get(0);

            // Find the forName(String) method
            List<Method> forNameMethods = classType.methodsByName("forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;");
            if (forNameMethods.isEmpty()) return;

            // Invoke Class.forName(className) in the target JVM
            StringReference classNameRef = vm.mirrorOf(className);
            classType.invokeMethod(threadRef, forNameMethods.get(0),
                    java.util.Collections.singletonList(classNameRef),
                    ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (Exception e) {
            // Non-fatal: the retry after this will produce a clear error
            System.err.println("[DurableThreads] Warning: failed to force-load class '"
                    + className + "' in target JVM: " + e.getMessage());
        }
    }
}
