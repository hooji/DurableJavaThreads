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

    /** Pre-resolved local variable entry for batch setValue. */
    static final class LocalEntry {
        private final com.sun.jdi.LocalVariable jdiLocal;
        private final Value jdiValue;
        private final boolean isNull;

        LocalEntry(com.sun.jdi.LocalVariable jdiLocal, Value jdiValue, boolean isNull) {
            this.jdiLocal = jdiLocal;
            this.jdiValue = jdiValue;
            this.isNull = isNull;
        }

        public com.sun.jdi.LocalVariable jdiLocal() { return jdiLocal; }
        public Value jdiValue() { return jdiValue; }
        public boolean isNull() { return isNull; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LocalEntry)) return false;
            LocalEntry that = (LocalEntry) o;
            return isNull == that.isNull
                    && Objects.equals(jdiLocal, that.jdiLocal)
                    && Objects.equals(jdiValue, that.jdiValue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jdiLocal, jdiValue, isNull);
        }

        @Override
        public String toString() {
            return "LocalEntry[jdiLocal=" + jdiLocal + ", jdiValue=" + jdiValue
                    + ", isNull=" + isNull + "]";
        }
    }

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

    /**
     * Bypass JDI's client-side type check and set a stack frame slot value directly.
     *
     * <p>On Java 8, JDI's {@code StackFrameImpl.setValue()} calls
     * {@code LocalVariableImpl.findType()} which searches only the declaring type's
     * classloader. Bootstrap-loaded classes (like {@code TimeUnit}) can't be found
     * when the declaring type uses the app classloader, causing
     * {@code ClassNotLoadedException} even though the class IS loaded.</p>
     *
     * <p>This method uses reflection to access JDI internals and send the
     * JDWP StackFrame.SetValues command directly, bypassing the type check.</p>
     */
    static boolean setValueBypassTypeCheck(StackFrame frame,
                                           com.sun.jdi.LocalVariable local,
                                           Value value) {
        try {
            // Get the slot number from LocalVariableImpl
            java.lang.reflect.Method slotMethod = local.getClass().getDeclaredMethod("slot");
            slotMethod.setAccessible(true);
            int slot = (int) slotMethod.invoke(local);

            // StackFrameImpl has: ThreadReferenceImpl thread, long id
            java.lang.reflect.Field threadField = frame.getClass().getDeclaredField("thread");
            threadField.setAccessible(true);
            Object threadImpl = threadField.get(frame);

            java.lang.reflect.Field idField = frame.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            long frameId = (long) idField.get(frame);

            // Get the VirtualMachineImpl
            java.lang.reflect.Method vmMethod = frame.getClass().getMethod("virtualMachine");
            Object vmImpl = vmMethod.invoke(frame);

            // Build JDWP.StackFrame.SetValues.SlotInfo
            Class<?> slotInfoClass = Class.forName(
                    "com.sun.tools.jdi.JDWP$StackFrame$SetValues$SlotInfo");
            java.lang.reflect.Constructor<?> slotInfoCtor = slotInfoClass.getDeclaredConstructor(
                    int.class, Value.class);
            slotInfoCtor.setAccessible(true);
            Object slotInfo = slotInfoCtor.newInstance(slot, value);

            // Create SlotInfo array
            Object slotInfoArray = java.lang.reflect.Array.newInstance(slotInfoClass, 1);
            java.lang.reflect.Array.set(slotInfoArray, 0, slotInfo);

            // Call JDWP.StackFrame.SetValues.process(vm, thread, frameId, slotInfos)
            // Find the method by name since class hierarchy varies across JDK versions
            Class<?> setValuesClass = Class.forName(
                    "com.sun.tools.jdi.JDWP$StackFrame$SetValues");
            java.lang.reflect.Method processMethod = null;
            for (java.lang.reflect.Method m : setValuesClass.getDeclaredMethods()) {
                if (m.getName().equals("process") && m.getParameterTypes().length == 4) {
                    processMethod = m;
                    break;
                }
            }
            if (processMethod == null) return false;
            processMethod.setAccessible(true);
            processMethod.invoke(null, vmImpl, threadImpl, frameId, slotInfoArray);
            return true;
        } catch (Exception e) {
            // Reflection failed — different JDI internal structure than expected
            return false;
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
